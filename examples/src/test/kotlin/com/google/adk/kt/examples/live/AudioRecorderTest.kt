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
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.sound.sampled.LineUnavailableException
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Tests [AudioRecorder] against a fake line, so no audio device is needed. */
class AudioRecorderTest {
  private val line = FakeDataLine()
  private val recorder = AudioRecorder { line }
  private val warnings = Collections.synchronizedList(mutableListOf<String>())
  private val handler =
    object : Handler() {
      override fun publish(record: LogRecord?) {
        if (record != null && record.level == Level.WARNING) warnings += record.message
      }

      override fun flush() {}

      override fun close() {}
    }
  private val logger = Logger.getLogger(AudioRecorder::class.java.name)

  @Before
  fun captureWarnings() {
    logger.addHandler(handler)
  }

  @After
  fun releaseWarnings() {
    logger.removeHandler(handler)
  }

  @Test
  fun mimeType_isInputPcmRate() {
    assertThat(recorder.mimeType).isEqualTo("audio/pcm;rate=16000")
  }

  @Test
  fun record_fullAndShortReads_emitsEachReadThenEndsOnAnEmptyRead(): Unit = runBlocking {
    line.reads += ByteArray(640) { 1 }
    line.reads += ByteArray(100) { 2 }
    line.reads += ByteArray(0)

    val frames = withTimeout(5.seconds) { recorder.record().toList() }

    assertThat(frames.map { it.size }).containsExactly(640, 100).inOrder()
    assertThat(frames[1]).isEqualTo(ByteArray(100) { 2 })
    assertThat(line.calls).containsExactly("open", "start", "stop", "close").inOrder()
  }

  @Test
  fun record_opensLineWithTenFrameBuffer(): Unit = runBlocking {
    line.reads += ByteArray(640)
    line.reads += ByteArray(0)

    withTimeout(5.seconds) { recorder.record().first() }

    assertThat(line.openedFormat!!.matches(javaSoundFormat(16_000))).isTrue()
    assertThat(line.openedBufferSize).isEqualTo(6_400)
  }

  @Test
  fun record_deviceStops_logsAWarning(): Unit = runBlocking {
    line.reads += ByteArray(0)

    withTimeout(5.seconds) { recorder.record().toList() }

    assertThat(warnings).hasSize(1)
  }

  @Test
  fun record_cancelledWhileReading_stopsAndClosesTheLineWithoutAWarning(): Unit = runBlocking {
    val recording = launch(Dispatchers.Default) { recorder.record().collect {} }
    assertThat(runInterruptible { line.readStarted.tryAcquire(5, TimeUnit.SECONDS) }).isTrue()

    recording.cancel()

    withTimeout(5.seconds) { recording.join() }
    assertThat(line.calls).containsExactly("open", "start", "stop", "close").inOrder()
    assertThat(warnings).isEmpty()
  }

  @Test
  fun record_cancelledDuringOpen_endsPromptlyWithoutWaitingOutTheTimeout(): Unit = runBlocking {
    val opening = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slowRecorder = AudioRecorder {
      opening.countDown()
      release.await()
      line
    }
    val recording = launch(Dispatchers.Default) { slowRecorder.record().collect {} }
    try {
      assertThat(runInterruptible { opening.await(5, TimeUnit.SECONDS) }).isTrue()
      withTimeout(4.seconds) { recording.cancelAndJoin() }
    } finally {
      release.countDown()
    }
  }

  @Test
  fun record_stopThrows_endsNormallyAndClosesTheLine(): Unit = runBlocking {
    line.stopThrows = true
    line.reads += ByteArray(0)

    val frames = withTimeout(5.seconds) { recorder.record().toList() }

    assertThat(frames).isEmpty()
    assertThat(line.calls).contains("close")
  }

  @Test
  fun record_startFails_closesTheLine(): Unit = runBlocking {
    line.startThrows = true

    assertFailsWith<IllegalStateException> { withTimeout(5.seconds) { recorder.record().toList() } }
    assertThat(line.calls).contains("close")
  }

  @Test
  fun record_lineThatFailsToOpen_throwsAudioDeviceException(): Unit = runBlocking {
    val recorder = AudioRecorder { throw LineUnavailableException("busy") }

    val error =
      assertFailsWith<AudioDeviceException> {
        withTimeout(5.seconds) { recorder.record().toList() }
      }
    assertThat(error).hasMessageThat().contains("cannot open the audio input device")
  }

  @Test
  fun record_readsOneBufferPerFrame(): Unit = runBlocking {
    line.reads += ByteArray(640)
    line.reads += ByteArray(0)

    withTimeout(5.seconds) { recorder.record().toList() }

    assertThat(line.readLengths.toSet()).isEqualTo(setOf(bytesPerFrame(16_000)))
  }

  @Test
  fun record_cancelledAsTheOpenCompletes_closesTheLineOnce(): Unit = runBlocking {
    val lines = CopyOnWriteArrayList<FakeDataLine>()
    // Cancel inside the open so its result races the cancel; 1,000 runs reliably catch a leak.
    repeat(1_000) {
      val jobRef = AtomicReference<Job?>()
      val recorder = AudioRecorder {
        val opened = FakeDataLine().also { lines += it }
        while (jobRef.get() == null) Thread.onSpinWait()
        jobRef.get()!!.cancel()
        opened
      }
      val job = launch(Dispatchers.Default) { recorder.record().collect {} }
      jobRef.set(job)
      withTimeout(10.seconds) { job.join() }
    }
    // Waits out the daemon closes; a leaked line never closes, so a leak fails here.
    withTimeout(5.seconds) { while (lines.any { "close" !in it.calls }) delay(10.milliseconds) }
    val closedTwice = lines.count { l ->
      synchronized(l.calls) { l.calls.count { it == "close" } } > 1
    }
    assertThat(closedTwice).isEqualTo(0)
  }
}
