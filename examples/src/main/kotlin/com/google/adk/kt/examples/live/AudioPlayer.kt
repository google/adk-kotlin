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
package com.google.adk.kt.examples.live

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val SAMPLE_RATE_HZ: Int = 24_000

/**
 * Plays 24 kHz, mono, 16-bit PCM through the device speaker, holding it open until [close].
 *
 * The constructor opens the speaker eagerly, blocking the caller for up to five seconds; [close]
 * drains queued audio and releases the device. Calling [play] after [close] throws
 * [IllegalStateException].
 */
internal class AudioPlayer(
  private val ioContext: CoroutineContext = Dispatchers.IO,
  getLine: (DataLine.Info) -> SourceDataLine = { AudioSystem.getLine(it) as SourceDataLine },
) : AutoCloseable {
  private val line: SourceDataLine =
    openConfiguredLine(SAMPLE_RATE_HZ, SourceDataLine::class.java, getLine) { format, bufferSize ->
      open(format, bufferSize)
      start()
    }

  // Bumped by discardQueuedAudio; a play that began before a bump writes no further slices.
  private val generation = AtomicLong()
  private val closed = AtomicBoolean()

  /**
   * Plays one chunk of whole 16-bit samples, suspending until the device accepts it; a discard or
   * [close] drops the rest. Throws [AudioDeviceException] if the device stops accepting audio.
   */
  suspend fun play(pcm: ByteArray) {
    require(pcm.size % BYTES_PER_SAMPLE == 0) {
      "pcm must be whole 16-bit samples, was ${pcm.size} bytes"
    }
    val queuedGeneration = generation.get()
    withContext(ioContext) {
      check(!closed.get()) { "player is closed" }
      var offset = 0
      val sliceBytes = lineBufferSizeBytes(SAMPLE_RATE_HZ)
      // Write one device buffer at a time so a discard, close or cancel stops within one slice.
      while (offset < pcm.size && generation.get() == queuedGeneration && !closed.get()) {
        // Stop a cancelled play before the next slice; a blocking write cannot see cancellation.
        currentCoroutineContext().ensureActive()
        val len = minOf(sliceBytes, pcm.size - offset)
        val written = line.write(pcm, offset, len)
        // Java Sound's write clears its flush flag on entry, so re-flush if a discard raced in.
        if (generation.get() != queuedGeneration) {
          line.flush()
        } else if (written < len && !closed.get()) {
          // Java Sound reports a device error, such as a lost speaker, as a short write.
          throw AudioDeviceException("the audio output device took $written of $len bytes")
        }
        offset += len
      }
    }
  }

  /** Drops audio queued on the device and the rest of any chunk a suspended [play] is writing. */
  fun discardQueuedAudio() {
    generation.incrementAndGet()
    line.flush()
  }

  override fun close() {
    if (closed.getAndSet(true)) return
    // Drained, not flushed, so audio already written plays out.
    line.use { it.drain() }
  }
}
