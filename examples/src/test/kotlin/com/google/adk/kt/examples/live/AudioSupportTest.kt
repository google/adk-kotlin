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

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.junit.Test

/** Tests the audio format helpers and the bounded device open, which need no device. */
class AudioSupportTest {

  /** An [AutoCloseable] stand-in for a line, so the device-open tests need no device. */
  private class FakeCloseable(private val onClose: () -> Unit = {}) : AutoCloseable {
    override fun close() {
      onClose()
    }
  }

  @Test
  fun javaSoundFormat_inputRate_isLittleEndianMono16BitPcm() {
    val format = javaSoundFormat(16_000)

    assertThat(format.encoding).isEqualTo(AudioFormat.Encoding.PCM_SIGNED)
    assertThat(format.sampleRate).isEqualTo(16_000f)
    assertThat(format.sampleSizeInBits).isEqualTo(16)
    assertThat(format.channels).isEqualTo(1)
    assertThat(format.frameSize).isEqualTo(2)
    assertThat(format.frameRate).isEqualTo(16_000f)
    assertThat(format.isBigEndian).isFalse()
  }

  @Test
  fun bytesPerFrame_knownRates_roundDownToWholeSamples() {
    assertThat(bytesPerFrame(16_000)).isEqualTo(640)
    assertThat(bytesPerFrame(24_000)).isEqualTo(960)
    // 11025 Hz over 20 ms is 220.5 samples; a frame must not end halfway through one.
    assertThat(bytesPerFrame(11_025)).isEqualTo(440)
  }

  @Test
  fun lineBufferSizeBytes_inputRate_holdsTenFrames() {
    assertThat(lineBufferSizeBytes(16_000)).isEqualTo(6_400)
  }

  @Test
  fun openDeviceLine_lineOpensInTime_returnsItUnclosed() {
    val closed = CountDownLatch(1)
    val line = FakeCloseable { closed.countDown() }

    val opened = openDeviceLine(direction = "input", mimeType = "audio/pcm;rate=16000") { line }

    assertThat(opened).isSameInstanceAs(line)
    assertThat(closed.await(200, TimeUnit.MILLISECONDS)).isFalse()
  }

  @Test
  fun openDeviceLine_openThatNeverReturns_throwsAfterTheTimeout() {
    val release = CountDownLatch(1)
    try {
      val error =
        assertFailsWith<AudioDeviceException> {
          openDeviceLine<FakeCloseable>(
            direction = "output",
            mimeType = "audio/pcm;rate=24000",
            timeout = 50.milliseconds,
          ) {
            release.await()
            error("unreachable")
          }
        }

      assertThat(error)
        .hasMessageThat()
        .contains("cannot open the audio output device for audio/pcm;rate=24000")
      assertThat(error).hasMessageThat().contains("(timed out after 50ms)")
    } finally {
      release.countDown()
    }
  }

  @Test
  fun openDeviceLine_lineOpenedAfterTheTimeout_isClosed() {
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)

    try {
      assertFailsWith<AudioDeviceException> {
        openDeviceLine(
          direction = "output",
          mimeType = "audio/pcm;rate=24000",
          timeout = 50.milliseconds,
        ) {
          release.await()
          FakeCloseable { closed.countDown() }
        }
      }
    } finally {
      release.countDown()
    }

    assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun openDeviceLine_audioDeviceExceptionFromOpen_isRethrownAsIs() {
    val thrown = AudioDeviceException("refused")

    val error =
      assertFailsWith<AudioDeviceException> {
        openDeviceLine<FakeCloseable>(direction = "input", mimeType = "audio/pcm;rate=16000") {
          throw thrown
        }
      }

    assertThat(error).isSameInstanceAs(thrown)
  }

  @Test
  fun openDeviceLine_lineUnavailable_throwsAudioDeviceException() {
    val error =
      assertFailsWith<AudioDeviceException> {
        openDeviceLine<FakeCloseable>(direction = "input", mimeType = "audio/pcm;rate=16000") {
          throw LineUnavailableException("busy")
        }
      }

    assertThat(error).hasMessageThat().contains("cannot open the audio input device")
    assertThat(error).hasCauseThat().isInstanceOf(LineUnavailableException::class.java)
  }

  @Test
  fun openDeviceLine_illegalArgumentFromOpen_throwsAudioDeviceException() {
    val error =
      assertFailsWith<AudioDeviceException> {
        openDeviceLine<FakeCloseable>(direction = "input", mimeType = "audio/pcm;rate=16000") {
          throw IllegalArgumentException("unsupported format")
        }
      }

    assertThat(error).hasCauseThat().isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun openDeviceLine_securityExceptionFromOpen_throwsAudioDeviceException() {
    val error =
      assertFailsWith<AudioDeviceException> {
        openDeviceLine<FakeCloseable>(direction = "input", mimeType = "audio/pcm;rate=16000") {
          throw SecurityException("no audio permission")
        }
      }

    assertThat(error).hasCauseThat().isInstanceOf(SecurityException::class.java)
  }

  @Test
  fun openDeviceLine_interruptedCaller_rethrowsAndClosesTheLateLine() {
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    var thrown: Throwable? = null
    val caller = Thread {
      thrown =
        runCatching {
            val unused =
              openDeviceLine(direction = "output", mimeType = "audio/pcm;rate=24000") {
                release.await()
                FakeCloseable { closed.countDown() }
              }
          }
          .exceptionOrNull()
    }

    caller.start()
    caller.interrupt()
    val callerEnded = caller.join(2.seconds.toJavaDuration())
    release.countDown()

    assertThat(callerEnded).isTrue()
    assertThat(thrown).isInstanceOf(InterruptedException::class.java)
    assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun openConfiguredLine_configureSucceeds_returnsTheLineConfiguredForTheRate() {
    val line = FakeCloseable()
    var configuredFormat: AudioFormat? = null
    var configuredBufferBytes = 0

    val opened =
      openConfiguredLine(
        sampleRateHz = 16_000,
        lineClass = FakeCloseable::class.java,
        getLine = { line },
      ) { format, bytes ->
        configuredFormat = format
        configuredBufferBytes = bytes
      }

    assertThat(opened).isSameInstanceAs(line)
    assertThat(configuredFormat?.matches(javaSoundFormat(16_000))).isTrue()
    assertThat(configuredBufferBytes).isEqualTo(6_400)
  }

  @Test
  fun openConfiguredLine_configureFails_closesTheLineAndRethrows() {
    val configureFailure = IllegalStateException("start failed")
    val closeFailure = IllegalStateException("close failed")
    var closed = false

    val error =
      assertFailsWith<IllegalStateException> {
        openConfiguredLine(
          sampleRateHz = 16_000,
          lineClass = FakeCloseable::class.java,
          getLine = {
            FakeCloseable {
              closed = true
              throw closeFailure
            }
          },
        ) { _, _ ->
          throw configureFailure
        }
      }

    assertThat(error).isSameInstanceAs(configureFailure)
    assertThat(closed).isTrue()
    assertThat(error.suppressedExceptions).containsExactly(closeFailure)
  }
}
