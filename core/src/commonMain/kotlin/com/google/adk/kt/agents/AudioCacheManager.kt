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

import com.google.adk.kt.artifacts.adkLiveArtifactReference
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.DEFAULT_AUDIO_INPUT_MIME_TYPE
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
 * It lives on the context, so a restarted turn keeps recording into it; a transfer or a completed
 * step saves it first, so the next agent starts empty. Its state stays private behind [add] and
 * [take], under a lock, because the send and receive coroutines both reach it.
 */
internal class RealtimeCache {
  private val lock = Mutex()
  private val entries = mutableListOf<RealtimeCacheEntry>()

  /**
   * Adds [blob] for [role], unless it holds no bytes. As in ADK Python and ADK Go, the cache has no
   * size limit; it empties only when a flush takes it.
   */
  suspend fun add(role: String, blob: Blob) {
    // An empty chunk adds no audio and would otherwise save an empty recording.
    if ((blob.data?.size ?: 0) == 0) return
    lock.withLock {
      entries.add(RealtimeCacheEntry(role, blob, Clock.System.now().toEpochMilliseconds()))
    }
  }

  /** Empties the cache under the lock and returns what it held, so a flush cannot race a chunk. */
  suspend fun take(): List<RealtimeCacheEntry> = lock.withLock {
    val taken = entries.toList()
    entries.clear()
    taken
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

  /**
   * Caches a chunk the caller sent; a chunk without a mime type is recorded as
   * [DEFAULT_AUDIO_INPUT_MIME_TYPE], the format it is sent to the model as.
   */
  suspend fun cacheInput(blob: Blob) {
    // Only audio input reaches here, so unlike Python a missing mime type still means audio.
    val mimeType = blob.mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_AUDIO_INPUT_MIME_TYPE
    if (!mimeType.startsWith("audio/")) return
    // A copy, since callers often refill one capture buffer for every chunk.
    context.frameworkData.inputRealtimeCache.add(
      Role.USER,
      blob.copy(mimeType = mimeType, data = blob.data?.copyOf()),
    )
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
  suspend fun flush(flushUserAudio: Boolean, flushModelAudio: Boolean): List<Event> =
    write(take(flushUserAudio, flushModelAudio))

  /** Empties the requested caches now, so a turn boundary stays exact while [write] runs later. */
  suspend fun take(flushUserAudio: Boolean, flushModelAudio: Boolean): TakenAudio =
    TakenAudio(
      input = if (flushUserAudio) context.frameworkData.inputRealtimeCache.take() else emptyList(),
      output =
        if (flushModelAudio) context.frameworkData.outputRealtimeCache.take() else emptyList(),
    )

  /** Writes [taken] out, returning one event per recording saved, as [flush] does. */
  suspend fun write(taken: TakenAudio): List<Event> =
    listOfNotNull(
      writeArtifact(taken.input, INPUT_AUDIO),
      writeArtifact(taken.output, OUTPUT_AUDIO),
    )

  /** Audio taken from the caches at one turn boundary. */
  class TakenAudio(val input: List<RealtimeCacheEntry>, val output: List<RealtimeCacheEntry>)

  private suspend fun writeArtifact(entries: List<RealtimeCacheEntry>, kind: String): Event? {
    val artifactService = context.artifactService
    if (entries.isEmpty() || artifactService == null) return null

    val first = entries.first()
    // Both caches hold a mime type: input gets the default when cached, and output must be audio.
    val mimeType = checkNotNull(first.data.mimeType)
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
    val reference = adkLiveArtifactReference(key, filename, revision)
    logger.debug { "Flushed $kind to the artifact service." }

    return Event(
      invocationId = context.invocationId,
      branch = context.branch,
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
    private val logger = LoggerFactory.getLogger(AudioCacheManager::class)
  }
}
