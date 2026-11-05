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

import java.util.logging.Logger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

private const val SAMPLE_RATE_HZ: Int = 16_000
private val logger = Logger.getLogger(AudioRecorder::class.java.name)

/**
 * Captures microphone audio as a cold flow of 16 kHz, mono, 16-bit PCM frames.
 *
 * The microphone opens when the flow is collected and closes when collection ends or is cancelled;
 * if the device stops on its own, the flow ends after logging a warning.
 */
internal class AudioRecorder(
  private val ioContext: CoroutineContext = Dispatchers.IO,
  private val getLine: (DataLine.Info) -> TargetDataLine = {
    AudioSystem.getLine(it) as TargetDataLine
  },
) {
  /** MIME type of the frames this recorder emits. */
  val mimeType: String = pcmMimeType(SAMPLE_RATE_HZ)

  /** Emits one 20 ms frame at a time, a short final read included. */
  fun record(): Flow<ByteArray> =
    flow {
        var line: TargetDataLine? = null
        try {
          // .also sets line even if runInterruptible drops the result on cancel; finally closes it.
          val mic = runInterruptible { openLine().also { line = it } }
          coroutineScope {
            // Java Sound's read ignores interrupts; UNDISPATCHED arms this stop before the read.
            @Suppress("UnsafeCoroutineCrossing") // Only the Java line is shared with the stopper.
            val stopper =
              launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                  awaitCancellation()
                } finally {
                  // Stopping is cleanup; its failure must not change how the flow ends.
                  try {
                    mic.stop()
                  } catch (e: RuntimeException) {
                    logger.fine { "Microphone stop failed: ${e.javaClass.simpleName}" }
                  }
                }
              }
            val frame = ByteArray(bytesPerFrame(SAMPLE_RATE_HZ))
            while (isActive) {
              val read = mic.read(frame, 0, frame.size)
              if (read <= 0) {
                // Warn only when the device stopped the line, not when we cancelled.
                if (isActive) {
                  logger.warning { "Microphone read returned $read; recording ended." }
                }
                break
              }
              emit(frame.copyOf(read))
            }
            stopper.cancel()
          }
        } finally {
          line?.close()
        }
      }
      .flowOn(ioContext)

  private fun openLine(): TargetDataLine =
    openConfiguredLine(SAMPLE_RATE_HZ, TargetDataLine::class.java, getLine) { format, bufferSize ->
      open(format, bufferSize)
      start()
    }
}
