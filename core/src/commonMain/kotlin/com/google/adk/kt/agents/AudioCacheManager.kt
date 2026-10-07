/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.agents

import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One chunk of realtime audio, kept until the turn that produced it ends. */
internal class RealtimeCacheEntry(
  /** [Role.USER] for audio the caller sent, [Role.MODEL] for audio the model spoke. */
  val role: String,
  val data: Blob,
  /** When the chunk was cached, in epoch milliseconds. */
  val timestamp: Long,
)

/**
 * Collects a live run's audio and writes it to the artifact service a turn at a time, as
 * `RunConfig.saveLiveBlob` asks.
 *
 * Chunks accumulate here and, when a turn ends (completion, or an interruption for the model's
 * side), are joined into one artifact referenced from one session event. Each cache is bounded by
 * [MAX_CACHE_BYTES] for runs whose turns stop completing, and every access goes through [lock]
 * because the send and receive coroutines both reach it.
 */
internal class AudioCacheManager(private val context: InvocationContext) {

  private val lock = Mutex()

  /**
   * Caches that have hit [MAX_CACHE_BYTES]; a capped cache rejects every later chunk so the
   * recording stays a clean prefix, and the warning is logged once.
   *
   * Cleared when the cache is flushed, so a drained cache records again on the next turn.
   */
  private val cappedRoles = mutableSetOf<String>()

  /** Bytes currently held per cache, so the cap is a running total, not a re-sum per chunk. */
  private val cachedBytes = mutableMapOf<String, Int>()

  /** Caches a chunk the caller sent; a chunk without a mime type is taken as PCM audio. */
  suspend fun cacheInput(blob: Blob) {
    // Only audio input reaches here, so unlike Python a missing mime type still means audio.
    if (blob.mimeType?.startsWith("audio/") == false) return
    cache(context.frameworkData.inputRealtimeCache, Role.USER, blob)
  }

  /** Caches a chunk the model spoke. */
  suspend fun cacheOutput(blob: Blob) {
    // Audio only: every downstream step names and joins the cache as one audio file.
    if (blob.mimeType?.startsWith("audio/") != true) return
    cache(context.frameworkData.outputRealtimeCache, Role.MODEL, blob)
  }

  /**
   * Writes the requested caches out, emitting one event per recording saved: at most one for the
   * caller and one for the model, each only when that cache held something.
   *
   * @param flushUserAudio whether to write out what the caller has said.
   * @param flushModelAudio whether to write out what the model has said.
   */
  suspend fun flush(flushUserAudio: Boolean, flushModelAudio: Boolean): List<Event> {
    val inputCache = context.frameworkData.inputRealtimeCache
    val outputCache = context.frameworkData.outputRealtimeCache
    val input = if (flushUserAudio) take(inputCache, Role.USER) else emptyList()
    val output = if (flushModelAudio) take(outputCache, Role.MODEL) else emptyList()
    return listOfNotNull(writeArtifact(input, INPUT_AUDIO), writeArtifact(output, OUTPUT_AUDIO))
  }

  private suspend fun cache(cache: MutableList<RealtimeCacheEntry>, role: String, blob: Blob) {
    lock.withLock {
      // A capped cache rejects every later chunk, so the recording stays a prefix with no gap.
      if (role in cappedRoles) return@withLock
      val incoming = blob.data?.size ?: 0
      if ((cachedBytes[role] ?: 0) + incoming > MAX_CACHE_BYTES) {
        cappedRoles.add(role)
        logger.warn {
          "Live audio recording for $role reached its ${MAX_CACHE_BYTES / (1024 * 1024)} MiB " +
            "cap and is no longer accepting chunks. The recording for this turn will be a " +
            "prefix. This happens when turns never complete, typically sustained interruption."
        }
        return@withLock
      }
      cache.add(RealtimeCacheEntry(role, blob, Clock.System.now().toEpochMilliseconds()))
      cachedBytes[role] = (cachedBytes[role] ?: 0) + incoming
    }
  }

  /** Empties a cache and returns what was in it, so a flush cannot race a concurrent chunk. */
  private suspend fun take(
    cache: MutableList<RealtimeCacheEntry>,
    role: String,
  ): List<RealtimeCacheEntry> = lock.withLock {
    val entries = cache.toList()
    cache.clear()
    // Draining resets the running total and lifts the cap, so the cache records again.
    cachedBytes[role] = 0
    cappedRoles.remove(role)
    entries
  }

  private suspend fun writeArtifact(entries: List<RealtimeCacheEntry>, kind: String): Event? {
    val artifactService = context.artifactService
    if (entries.isEmpty() || artifactService == null) return null

    val first = entries.first()
    val mimeType = first.data.mimeType ?: DEFAULT_MIME_TYPE
    // Joined once; appending per chunk would be quadratic in the total audio.
    val combined = ByteArray(entries.sumOf { it.data.data?.size ?: 0 })
    var offset = 0
    for (entry in entries) {
      val bytes = entry.data.data ?: continue
      bytes.copyInto(combined, offset)
      offset += bytes.size
    }

    // Mime type and timestamp come from the FIRST chunk, so the name records when it began.
    val filename =
      "adk_live_audio_storage_${kind}_${first.timestamp}.${mimeType.substringAfterLast('/')}"
    val revision =
      try {
        artifactService.saveArtifact(
          context.session.key,
          filename,
          Part(inlineData = Blob(mimeType = mimeType, data = combined)),
        )
      } catch (e: CancellationException) {
        // The run itself is going away; that is not this function's to swallow.
        throw e
      } catch (e: Exception) {
        // Log only the type (errors name users and files) and drop the audio, as in Python.
        logger.error { "Failed to flush $kind to the artifact service (${e::class.simpleName})." }
        return null
      }

    val key = context.session.key
    val reference =
      "artifact://${key.appName}/${key.userId}/${key.id}/$LIVE_DIR/$filename#$revision"
    logger.debug { "Flushed $kind to the artifact service." }

    return Event(
      invocationId = context.invocationId,
      // The model's audio is authored by this agent; the user's has no agent to name.
      author = if (first.role == Role.MODEL) context.agent.name else first.role,
      content =
        Content(
          role = first.role,
          parts = listOf(Part(fileData = FileData(fileUri = reference, mimeType = mimeType))),
        ),
      timestamp = first.timestamp,
    )
  }

  private companion object {
    private const val INPUT_AUDIO = "input_audio"
    private const val OUTPUT_AUDIO = "output_audio"
    private const val DEFAULT_MIME_TYPE = "audio/pcm"

    /**
     * Per cache, so one stuck side cannot grow unbounded: about three minutes of 24 kHz model audio
     * or four of 16 kHz input, both 16-bit mono.
     */
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private const val LIVE_DIR = "_adk_live"
    private val logger = LoggerFactory.getLogger(AudioCacheManager::class)
  }
}
