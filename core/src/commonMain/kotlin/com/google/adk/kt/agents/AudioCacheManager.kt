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
 * One side's audio since the last flush.
 *
 * It lives on the context, so a live transfer carries the audio and its cap into the next agent's
 * turn. Its state stays private behind [add] and [take], under a lock, because the send and receive
 * coroutines both reach it.
 */
internal class RealtimeCache {
  private val lock = Mutex()
  private val entries = mutableListOf<RealtimeCacheEntry>()
  private var bytes = 0
  private var capped = false

  /**
   * Adds [blob] for [role], unless it holds no bytes or the cache is capped. A chunk that would
   * take the cache past [MAX_CACHE_BYTES] caps it instead.
   */
  suspend fun add(role: String, blob: Blob) {
    val incoming = blob.data?.size ?: 0
    // An empty chunk adds no audio and would otherwise save an empty recording.
    if (incoming == 0) return
    lock.withLock {
      // A capped cache rejects every later chunk, so the recording stays a prefix with no gap.
      if (capped) return@withLock
      if (bytes + incoming > MAX_CACHE_BYTES) {
        capped = true
        logger.warn {
          "Live audio recording for $role reached its ${MAX_CACHE_BYTES / (1024 * 1024)} MiB " +
            "cap and is no longer accepting chunks. The recording for this turn will be a " +
            "prefix. This happens when a turn runs long or never completes, as under sustained " +
            "interruption."
        }
        return@withLock
      }
      entries.add(RealtimeCacheEntry(role, blob, Clock.System.now().toEpochMilliseconds()))
      bytes += incoming
    }
  }

  /** Empties the cache under the lock and returns what it held, so a flush cannot race a chunk. */
  suspend fun take(): List<RealtimeCacheEntry> = lock.withLock {
    val taken = entries.toList()
    entries.clear()
    // Draining resets the running total and lifts the cap, so the cache records again.
    bytes = 0
    capped = false
    taken
  }

  private companion object {
    /**
     * Per cache, so one stuck side cannot grow unbounded: about three minutes of 24 kHz model audio
     * or four of 16 kHz input, both 16-bit mono.
     */
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private val logger = LoggerFactory.getLogger(RealtimeCache::class)
  }
}

/**
 * Collects a live run's audio and writes it to the artifact service a turn at a time, as
 * [RunConfig.saveLiveBlob] asks.
 *
 * Chunks accumulate on the context's [RealtimeCache]s. When a turn ends, [flush] joins each side's
 * chunks into one artifact and returns an event that references it.
 */
internal class AudioCacheManager(private val context: InvocationContext) {

  /** Caches a chunk the caller sent; a chunk without a mime type is taken as PCM audio. */
  suspend fun cacheInput(blob: Blob) {
    // Only audio input reaches here, so unlike Python a missing mime type still means audio.
    if (blob.mimeType?.startsWith("audio/") == false) return
    context.frameworkData.inputRealtimeCache.add(Role.USER, blob)
  }

  /** Caches a chunk the model spoke. */
  suspend fun cacheOutput(blob: Blob) {
    // Audio only: every downstream step names and joins the cache as one audio file.
    if (blob.mimeType?.startsWith("audio/") != true) return
    context.frameworkData.outputRealtimeCache.add(Role.MODEL, blob)
  }

  /**
   * Writes the requested caches out, returning one event per recording saved: at most one for the
   * caller and one for the model, each only when that cache held something. Audio whose save fails
   * or is cancelled is dropped, as in ADK Python.
   *
   * @param flushUserAudio whether to write out what the caller has said.
   * @param flushModelAudio whether to write out what the model has said.
   */
  suspend fun flush(flushUserAudio: Boolean, flushModelAudio: Boolean): List<Event> {
    val input = if (flushUserAudio) context.frameworkData.inputRealtimeCache.take() else emptyList()
    val output =
      if (flushModelAudio) context.frameworkData.outputRealtimeCache.take() else emptyList()
    return listOfNotNull(writeArtifact(input, INPUT_AUDIO), writeArtifact(output, OUTPUT_AUDIO))
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
    val extension = mimeType.substringBefore(';').trim().substringAfterLast('/')
    val filename = "adk_live_audio_storage_${kind}_${first.timestamp}.$extension"
    val revision =
      try {
        artifactService.saveArtifact(
          context.session.key,
          filename,
          Part(inlineData = Blob(mimeType = mimeType, data = combined)),
        )
      } catch (e: CancellationException) {
        // Rethrow coroutine cancellation so catching Exception below does not swallow it.
        throw e
      } catch (e: Exception) {
        // Drop the audio, as Python does, but log only the type: errors can name users and files.
        logger.error { "Failed to flush $kind to the artifact service (${e::class.simpleName})." }
        return null
      }

    val key = context.session.key
    // As in ADK Python, the reference adds a _adk_live/ segment the saved name does not have.
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
    private const val LIVE_DIR = "_adk_live"
    private val logger = LoggerFactory.getLogger(AudioCacheManager::class)
  }
}
