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
import javax.sound.sampled.LineUnavailableException
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** Tests [AudioPlayer] against a fake line, so no audio device is needed. */
class AudioPlayerTest {
  private val line = FakeDataLine()

  @Test
  fun constructor_opensAndStartsLineWithTenFrameBuffer() {
    val unused = AudioPlayer { line }

    assertThat(line.calls).containsExactly("open", "start").inOrder()
    assertThat(line.openedFormat!!.matches(javaSoundFormat(sampleRateHz = 24_000))).isTrue()
    assertThat(line.openedBufferSize).isEqualTo(9_600)
  }

  @Test
  fun play_chunk_writesItToTheLine(): Unit = runBlocking {
    val player = AudioPlayer { line }

    player.play(byteArrayOf(1, 2, 3, 4))

    assertThat(line.written.single()).isEqualTo(byteArrayOf(1, 2, 3, 4))
  }

  @Test
  fun play_oddNumberOfBytes_throwsIllegalArgumentException(): Unit = runBlocking {
    val player = AudioPlayer { line }

    assertFailsWith<IllegalArgumentException> { player.play(byteArrayOf(1, 2, 3)) }
    assertThat(line.calls).doesNotContain("write")
  }

  @Test
  fun play_afterClose_throwsIllegalStateException(): Unit = runBlocking {
    val player = AudioPlayer { line }
    player.close()

    assertFailsWith<IllegalStateException> { player.play(ByteArray(960)) }
    assertThat(line.calls).doesNotContain("write")
  }

  @Test
  fun play_lineStopsAcceptingAudio_throwsAudioDeviceException(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.writeAccepts = 0

    assertFailsWith<AudioDeviceException> { player.play(ByteArray(960)) }
  }

  @Test
  fun play_shortWriteFromADiscard_returnsNormally(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.writeAccepts = 0
    line.onWriteEntry = { player.discardQueuedAudio() }

    player.play(ByteArray(960))

    assertThat(line.calls.drop(line.calls.lastIndexOf("write") + 1)).contains("flush")
  }

  @Test
  fun play_shortWriteFromAClose_returnsNormally(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.writeAccepts = 0
    line.onWriteEntry = { player.close() }

    player.play(ByteArray(960))

    assertThat(line.calls).contains("close")
  }

  @Test
  fun discardQueuedAudio_duringBlockedPlay_flushesAndUnblocksIt(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.blockWrites = true
    val playing = async(Dispatchers.Default) { player.play(ByteArray(960)) }
    try {
      assertThat(runInterruptible { line.writeEntered.await(5, TimeUnit.SECONDS) }).isTrue()

      player.discardQueuedAudio()

      withTimeout(5.seconds) { playing.await() }
      assertThat(line.calls).contains("flush")
    } finally {
      line.flush()
    }
  }

  @Test
  fun play_discardedBeforeTheWrite_skipsTheStaleChunk(): Unit = runBlocking {
    val dispatcher = GatedDispatcher()
    val player = AudioPlayer(dispatcher) { line }
    val playing = launch(Dispatchers.Default) { player.play(ByteArray(960)) }
    try {
      assertThat(dispatcher.awaitArrival()).isTrue()
      player.discardQueuedAudio()
    } finally {
      dispatcher.release()
    }

    withTimeout(5.seconds) { playing.join() }
    assertThat(line.calls).doesNotContain("write")
    assertThat(line.written).isEmpty()
  }

  @Test
  fun play_discardedAsTheWriteBegins_reflushesToDropTheStaleChunk(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.onWriteEntry = { player.discardQueuedAudio() }

    player.play(ByteArray(960))

    val lastWrite = line.calls.lastIndexOf("write")
    assertThat(line.calls.drop(lastWrite + 1)).contains("flush")
  }

  @Test
  fun play_chunkLongerThanTheLineBuffer_writesInBufferSizedSlices(): Unit = runBlocking {
    val player = AudioPlayer { line }

    player.play(ByteArray(BUFFER_BYTES * 2 + 640))

    assertThat(line.written.map { it.size })
      .containsExactly(BUFFER_BYTES, BUFFER_BYTES, 640)
      .inOrder()
  }

  @Test
  fun play_discardedDuringAMultiSliceChunk_writesNoFurtherSlices(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.onWriteEntry = { player.discardQueuedAudio() }

    player.play(ByteArray(BUFFER_BYTES * 3))

    assertThat(line.written).hasSize(1)
  }

  @Test
  fun discardQueuedAudio_thenPlay_writesTheNextChunk(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.onWriteEntry = { player.discardQueuedAudio() }
    player.play(ByteArray(BUFFER_BYTES * 2))

    player.play(byteArrayOf(5, 6))

    assertThat(line.written.map { it.size }).containsExactly(BUFFER_BYTES, 2).inOrder()
    assertThat(line.written.last()).isEqualTo(byteArrayOf(5, 6))
  }

  @Test
  fun play_closedDuringAMultiSliceChunk_writesNoFurtherSlices(): Unit = runBlocking {
    val player = AudioPlayer { line }
    line.onWriteEntry = { player.close() }

    player.play(ByteArray(BUFFER_BYTES * 2))

    assertThat(line.written).hasSize(1)
  }

  @Test
  fun play_cancelledDuringAMultiSliceChunk_writesNoFurtherSlices(): Unit = runBlocking {
    val player = AudioPlayer { line }
    val job =
      launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        player.play(ByteArray(BUFFER_BYTES * 2))
      }
    // Cancel as the first slice begins; the per-slice ensureActive must stop before the second.
    line.onWriteEntry = { job.cancel() }
    job.start()

    withTimeout(5.seconds) { job.join() }
    assertThat(line.written).hasSize(1)
  }

  @Test
  fun close_queuedAudio_drainsBeforeClosing(): Unit = runBlocking {
    val player = AudioPlayer { line }
    player.play(ByteArray(960))
    line.calls.clear()

    player.close()

    assertThat(line.calls).containsExactly("drain", "close").inOrder()
  }

  @Test
  fun close_calledTwice_drainsOnce() {
    val player = AudioPlayer { line }
    line.calls.clear()

    player.close()
    player.close()

    assertThat(line.calls).containsExactly("drain", "close").inOrder()
  }

  @Test
  fun close_drainThrows_stillClosesTheLine() {
    val player = AudioPlayer { line }
    line.drainThrows = true
    line.calls.clear()

    assertFailsWith<IllegalStateException> { player.close() }
    assertThat(line.calls).contains("close")
  }

  @Test
  fun play_whileClosingDrains_isRejected(): Unit = runBlocking {
    val player = AudioPlayer { line }
    val draining = CountDownLatch(1)
    val releaseDrain = CountDownLatch(1)
    line.onDrain = {
      draining.countDown()
      releaseDrain.await()
    }
    val closing = launch(Dispatchers.Default) { player.close() }
    try {
      assertThat(runInterruptible { draining.await(5, TimeUnit.SECONDS) }).isTrue()
      assertFailsWith<IllegalStateException> { player.play(ByteArray(960)) }
    } finally {
      releaseDrain.countDown()
    }
    withTimeout(5.seconds) { closing.join() }
    assertThat(line.calls).doesNotContain("write")
  }

  @Test
  fun constructor_startFails_closesTheLine() {
    line.startThrows = true

    assertFailsWith<IllegalStateException> { AudioPlayer { line } }
    assertThat(line.calls).contains("close")
  }

  @Test
  fun constructor_lineThatFailsToOpen_throwsAudioDeviceException() {
    val error =
      assertFailsWith<AudioDeviceException> {
        AudioPlayer { throw LineUnavailableException("busy") }
      }

    assertThat(error).hasMessageThat().contains("cannot open the audio output device")
  }

  /** Runs a dispatched block only after [release], so a test can act while it is pending. */
  private class GatedDispatcher : CoroutineDispatcher() {
    private val arrived = CountDownLatch(1)
    private val gate = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      thread {
        arrived.countDown()
        gate.await()
        block.run()
      }
    }

    fun awaitArrival(): Boolean = arrived.await(5, TimeUnit.SECONDS)

    fun release() {
      gate.countDown()
    }
  }

  companion object {
    private const val BUFFER_BYTES = 9_600
  }
}
