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

package com.google.adk.kt.examples.live;

import static com.google.adk.kt.examples.live.AudioSupportJava.javaSoundFormat;
import static com.google.adk.kt.examples.live.FakeDataLineJava.LONG_WAIT_SECONDS;
import static com.google.adk.kt.examples.live.FakeDataLineJava.awaitQuietly;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.adk.kt.examples.live.AudioSupportJava.AudioDeviceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.LineUnavailableException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link AudioPlayerJava} against a fake line, so no audio device is needed. */
@RunWith(JUnit4.class)
public final class AudioPlayerJavaTest {

  private final List<Throwable> errors = new CopyOnWriteArrayList<>();
  private final CountDownLatch firstError = new CountDownLatch(1);

  private void report(Throwable error) {
    errors.add(error);
    firstError.countDown();
  }

  private AudioPlayerJava newPlayer(FakeDataLineJava line) {
    return newPlayer(line, Duration.ofSeconds(1));
  }

  private AudioPlayerJava newPlayer(FakeDataLineJava line, Duration drainTimeout) {
    return new AudioPlayerJava(info -> line, Duration.ofSeconds(1), drainTimeout, this::report);
  }

  @Test
  public void constructor_opensAndStartsLineWithTenFrameBuffer() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();

    try (AudioPlayerJava player = newPlayer(line)) {

      assertThat(line.calls).containsExactly("open", "start").inOrder();
      assertThat(line.openedFormat.matches(javaSoundFormat(24_000))).isTrue();
      assertThat(line.openedBufferSize).isEqualTo(9_600);
      player.close();
    }
  }

  @Test
  public void constructor_startFails_closesTheLine() {
    FakeDataLineJava line = new FakeDataLineJava();
    line.startThrows = true;

    assertThrows(IllegalStateException.class, () -> newPlayer(line));
    assertThat(line.calls).contains("close");
  }

  @Test
  public void constructor_lineThatFailsToOpen_throwsAudioDeviceException() {
    FakeDataLineJava line = new FakeDataLineJava();
    LineUnavailableException refused = new LineUnavailableException("busy");
    line.openFailure = refused;

    AudioDeviceException e = assertThrows(AudioDeviceException.class, () -> newPlayer(line));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo("cannot open the audio output device for audio/pcm;rate=24000");
    assertThat(e).hasCauseThat().isSameInstanceAs(refused);
    assertThat(line.calls).contains("close");
  }

  @Test
  public void play_chunk_writesItToTheLine() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    try (AudioPlayerJava player = newPlayer(line)) {

      player.play(chunk(7, 4));
      player.close();

      assertThat(line.writtenIds).containsExactly(7);
      assertThat(line.writeSizes).containsExactly(4);
    }
  }

  @Test
  public void play_chunkLongerThanTheLineBuffer_writesInBufferSizedSlices() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    try (AudioPlayerJava player = newPlayer(line)) {

      player.play(new byte[9_600 * 2 + 640]);
      player.close();

      assertThat(line.writeSizes).containsExactly(9_600, 9_600, 640).inOrder();
    }
  }

  @Test
  public void play_whileTheDeviceIsBlocked_returnsAtOnceAndKeepsOrder() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line)) {
      try {
        for (int i = 0; i < 5; i++) {
          player.play(chunk(i, 960));
        }
        assertThat(line.writtenIds).isEmpty();
      } finally {
        line.releaseWrites();
      }
      player.close();

      assertThat(line.writtenIds).containsExactly(0, 1, 2, 3, 4).inOrder();
      assertThat(errors).isEmpty();
    }
  }

  @Test
  public void play_oddNumberOfBytes_throwsIllegalArgumentException() throws Exception {
    try (AudioPlayerJava player = newPlayer(new FakeDataLineJava())) {

      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> player.play(new byte[3]));

      assertThat(e).hasMessageThat().isEqualTo("pcm must be whole 16-bit samples, was 3 bytes");
    }
  }

  @Test
  public void play_afterClose_throwsIllegalStateException() throws Exception {
    try (AudioPlayerJava player = newPlayer(new FakeDataLineJava())) {
      player.close();

      IllegalStateException e =
          assertThrows(IllegalStateException.class, () -> player.play(new byte[960]));

      assertThat(e).hasMessageThat().isEqualTo("player is closed");
    }
  }

  @Test
  public void play_writeFails_reportsTheErrorOnce() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    IllegalStateException failure = new IllegalStateException("device gone");
    line.writeFailure = failure;
    try (AudioPlayerJava player = newPlayer(line)) {

      player.play(chunk(1, 960));
      player.play(chunk(2, 960));

      assertThat(firstError.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      player.close();
      assertThat(errors).containsExactly(failure);
    }
  }

  @Test
  public void play_whileClosingDrains_isRejected() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch draining = new CountDownLatch(1);
    CountDownLatch releaseDrain = new CountDownLatch(1);
    line.onDrain =
        () -> {
          draining.countDown();
          awaitQuietly(releaseDrain);
        };
    try (AudioPlayerJava player = newPlayer(line)) {
      Thread closing = new Thread(player::close);
      closing.start();
      try {
        assertThat(draining.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
        assertThrows(IllegalStateException.class, () -> player.play(new byte[960]));
      } finally {
        releaseDrain.countDown();
      }
      assertThat(closing.join(Duration.ofSeconds(LONG_WAIT_SECONDS))).isTrue();
      assertThat(line.calls).doesNotContain("write");
    }
  }

  @Test
  public void close_queuedAudio_drainsBeforeClosing() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line)) {
      player.play(chunk(1, 960));
      player.play(chunk(2, 960));
      line.releaseWrites();

      player.close();

      assertThat(line.writtenIds).containsExactly(1, 2).inOrder();
      assertThat(line.calls.subList(line.calls.indexOf("drain"), line.calls.size()))
          .containsExactly("drain", "close")
          .inOrder();
    }
  }

  @Test
  public void close_calledTwice_drainsOnce() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    try (AudioPlayerJava player = newPlayer(line)) {
      line.calls.clear();

      player.close();
      player.close();

      assertThat(line.calls).containsExactly("drain", "close").inOrder();
    }
  }

  @Test
  public void close_interrupted_closesTheLineAndKeepsTheInterrupt() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    AudioPlayerJava player = newPlayer(line);
    player.play(chunk(1, 960));
    Thread.currentThread().interrupt();
    try {

      player.close();

      assertThat(Thread.interrupted()).isTrue();
      assertThat(line.calls).containsAtLeast("flush", "close").inOrder();
    } finally {
      Thread.interrupted();
      line.releaseWrites();
    }
  }

  @Test
  public void close_drainThrows_reportsItAndStillClosesTheLine() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.drainThrows = true;
    try (AudioPlayerJava player = newPlayer(line)) {

      // The writer thread drains, so the failure goes to onError instead of out of close().
      player.close();

      assertThat(line.calls).contains("close");
      assertThat(errors).hasSize(1);
      assertThat(errors.get(0)).hasMessageThat().isEqualTo("drain failed");
    }
  }

  @Test
  public void close_fromOnErrorOnTheWriterThread_returnsWithoutWaitingForTheDrain()
      throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.writeFailure = new IllegalStateException("device gone");
    AtomicReference<AudioPlayerJava> player = new AtomicReference<>();
    CountDownLatch closedOnWriter = new CountDownLatch(1);
    // A drain timeout far past the wait below, so close() waiting on its own thread would fail.
    player.set(
        new AudioPlayerJava(
            info -> line,
            Duration.ofSeconds(1),
            Duration.ofMinutes(1),
            error -> {
              player.get().close();
              closedOnWriter.countDown();
            }));
    try {
      player.get().play(chunk(1, 960));

      assertThat(closedOnWriter.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      assertThat(line.calls).contains("close");
    } finally {
      player.get().close();
    }
  }

  @Test
  public void close_duringAMultiSliceChunk_playsOutTheRestOfIt() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line, Duration.ofSeconds(LONG_WAIT_SECONDS))) {
      player.play(chunk(1, 9_600 * 2));
      assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      Thread closing = new Thread(player::close);
      closing.start();
      // Release the write only once close() waits on the writer, so the play-out overlaps it.
      while (closing.getState() != Thread.State.TIMED_WAITING) {
        assertThat(closing.isAlive()).isTrue();
        Thread.sleep(1);
      }
      line.releaseWrites();

      assertThat(closing.join(Duration.ofSeconds(LONG_WAIT_SECONDS))).isTrue();
      assertThat(line.writeSizes).containsExactly(9_600, 9_600).inOrder();
      assertThat(errors).isEmpty();
    }
  }

  @Test
  public void play_lineStopsAcceptingAudio_reportsAnAudioDeviceExceptionAndStops()
      throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.writeAccepts = 0;
    try (AudioPlayerJava player = newPlayer(line)) {

      player.play(chunk(1, 9_600 * 2));

      assertThat(firstError.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      assertThat(line.writerEnded()).isTrue();
      assertThat(errors.get(0)).isInstanceOf(AudioDeviceException.class);
      assertThat(line.writeSizes).hasSize(1);
    }
  }

  @Test
  public void play_shortWriteFromADiscard_isNotAnError() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.writeAccepts = 0;
    try (AudioPlayerJava player = newPlayer(line)) {
      line.onWriteEntry = player::discardQueuedAudio;

      player.play(chunk(1, 960));
      assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      // close() waits for the writer, so an error from that write would be reported by now.
      player.close();

      assertThat(errors).isEmpty();
    }
  }

  @Test
  public void play_shortWriteFromACutOffClose_isNotAnError() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.writeAccepts = 0;
    try (AudioPlayerJava player = newPlayer(line)) {
      // On the writer thread, close() cuts the play-out off at once.
      line.onWriteEntry = player::close;

      player.play(chunk(1, 960));

      assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      assertThat(line.writerEnded()).isTrue();
      assertThat(errors).isEmpty();
    }
  }

  @Test
  public void close_hitsItsBound_cutsOffWithNoLineCallsOrErrorsAfterwards() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line, Duration.ofMillis(50))) {
      for (int i = 0; i < 5; i++) {
        player.play(chunk(i, 960));
      }
      try {
        assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();

        player.close();
        // close() returned while the first write was still blocked.
        assertThat(line.writeSizes).isEmpty();
      } finally {
        line.releaseWrites();
      }

      assertThat(line.writerEnded()).isTrue();
      assertThat(afterLast(line.calls, "close")).isEmpty();
      assertThat(errors).isEmpty();
    }
  }

  @Test
  public void close_cutOffWhileAWriteFails_reportsNothingAndSkipsTheDrain() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line, Duration.ofMillis(50))) {
      player.play(chunk(0, 960));
      try {
        assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();

        player.close();
        line.writeFailure = new IllegalStateException("line closed");
      } finally {
        line.releaseWrites();
      }

      assertThat(line.writerEnded()).isTrue();
      assertThat(errors).isEmpty();
      assertThat(line.calls).doesNotContain("drain");
    }
  }

  @Test
  public void close_drainOutlastsTheBound_cutsOffQuietly() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch releaseDrain = new CountDownLatch(1);
    line.onDrain = () -> awaitQuietly(releaseDrain);
    line.drainThrows = true;
    try (AudioPlayerJava player = newPlayer(line, Duration.ofMillis(500))) {
      player.play(chunk(1, 960));
      try {

        player.close();

        assertThat(line.calls).containsAtLeast("drain", "flush", "close").inOrder();
      } finally {
        releaseDrain.countDown();
      }
      assertThat(line.writerEnded()).isTrue();
      assertThat(errors).isEmpty();
      assertThat(afterLast(line.calls, "close")).isEmpty();
    }
  }

  @Test
  public void close_slowDrain_waitsForItWithinTheBound() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch draining = new CountDownLatch(1);
    CountDownLatch releaseDrain = new CountDownLatch(1);
    line.onDrain =
        () -> {
          draining.countDown();
          awaitQuietly(releaseDrain);
          line.calls.add("drained");
        };
    try (AudioPlayerJava player = newPlayer(line, Duration.ofSeconds(LONG_WAIT_SECONDS))) {
      player.play(chunk(1, 960));
      Thread closing = new Thread(player::close);
      closing.start();
      try {
        assertThat(draining.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
        // close() is still waiting on the drain.
        assertThat(closing.join(Duration.ofMillis(200))).isFalse();
      } finally {
        releaseDrain.countDown();
      }
      assertThat(closing.join(Duration.ofSeconds(LONG_WAIT_SECONDS))).isTrue();
      assertThat(line.calls).containsAtLeast("drained", "close").inOrder();
      assertThat(line.calls).doesNotContain("flush");
    }
  }

  @Test
  public void discardQueuedAudio_duringBlockedPlay_flushesAndSkipsTheQueuedChunk()
      throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line)) {
      player.play(chunk(1, 960 * 30));
      player.play(chunk(2, 960));
      try {
        assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();

        player.discardQueuedAudio();
      } finally {
        line.releaseWrites();
      }
      player.play(chunk(3, 960));
      player.close();

      // Chunk 1 stops after the slice already in flight; chunk 2 never plays.
      assertThat(line.writtenIds).containsExactly(1, 3).inOrder();
      assertThat(line.writeSizes).containsExactly(9_600, 960).inOrder();
      assertThat(line.calls).contains("flush");
    }
  }

  @Test
  public void discardQueuedAudio_flushesTheSpeakerFromTheCaller() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    Thread testThread = Thread.currentThread();
    AtomicInteger callerFlushes = new AtomicInteger();
    line.onFlush =
        by -> {
          if (by == testThread) {
            callerFlushes.incrementAndGet();
          }
        };
    CountDownLatch written = new CountDownLatch(1);
    line.onWrite = id -> written.countDown();
    try (AudioPlayerJava player = newPlayer(line)) {
      player.play(chunk(1, 960));
      assertThat(written.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();

      player.discardQueuedAudio();

      assertThat(callerFlushes.get()).isEqualTo(1);
      player.close();
    }
  }

  @Test
  public void discardQueuedAudio_afterClose_leavesTheLineAlone() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    try (AudioPlayerJava player = newPlayer(line)) {
      player.close();

      player.discardQueuedAudio();

      assertThat(afterLast(line.calls, "close")).isEmpty();
    }
  }

  @Test
  public void discardQueuedAudio_thenCutOffClose_touchesTheLineNoMore() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.blockWrites();
    try (AudioPlayerJava player = newPlayer(line, Duration.ofMillis(50))) {
      player.play(chunk(1, 960));
      try {
        assertThat(line.writeEntered.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();

        player.discardQueuedAudio();
        player.close();
      } finally {
        line.releaseWrites();
      }

      assertThat(line.writerEnded()).isTrue();
      assertThat(afterLast(line.calls, "close")).isEmpty();
    }
  }

  @Test
  public void play_discardedAsTheWriteBegins_reflushesToDropTheStaleChunk() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    try (AudioPlayerJava player = newPlayer(line)) {
      CountDownLatch discarded = new CountDownLatch(1);
      line.onWriteEntry =
          () -> {
            player.discardQueuedAudio();
            discarded.countDown();
          };

      player.play(chunk(1, 960));
      assertThat(discarded.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      player.close();

      List<String> afterWrite = afterLast(line.calls, "write");
      assertThat(afterWrite).contains("flush");
    }
  }

  @Test
  public void play_discardedDuringAMultiSliceChunk_writesNoFurtherSlices() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    try (AudioPlayerJava player = newPlayer(line)) {
      CountDownLatch discarded = new CountDownLatch(1);
      line.onWriteEntry =
          () -> {
            player.discardQueuedAudio();
            discarded.countDown();
          };

      player.play(new byte[9_600 * 3]);
      assertThat(discarded.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      player.close();

      assertThat(line.writeSizes).hasSize(1);
    }
  }

  @Test
  public void play_discardRacingTheWriter_neverPlaysAStaleChunkUncut() throws Exception {
    AtomicInteger currentId = new AtomicInteger(-1);
    AtomicInteger lastDiscardedId = new AtomicInteger(-1);
    AtomicInteger pendingStaleId = new AtomicInteger(-1);
    AtomicInteger uncutStaleWrites = new AtomicInteger();
    FakeDataLineJava line = new FakeDataLineJava();
    // A discarded chunk's write counts as uncut unless the writer flushes before its next write.
    line.onWrite =
        id -> {
          if (pendingStaleId.getAndSet(-1) >= 0) {
            uncutStaleWrites.incrementAndGet();
          }
          if (id <= lastDiscardedId.get()) {
            pendingStaleId.set(id);
          }
        };
    Thread testThread = Thread.currentThread();
    line.onFlush =
        by -> {
          if (by == testThread) {
            lastDiscardedId.set(currentId.get());
          } else {
            pendingStaleId.set(-1);
          }
        };
    try (AudioPlayerJava player = newPlayer(line)) {
      Random random = new Random(1);

      // The window is the writer's take() racing a discard, so many tries make a regression show.
      for (int id = 0; id < 30_000; id++) {
        currentId.set(id);
        player.play(chunk(id, 960));
        long until = System.nanoTime() + random.nextInt(20_000);
        while (System.nanoTime() < until) {
          Thread.onSpinWait();
        }
        player.discardQueuedAudio();
      }
      player.close();
      if (pendingStaleId.get() >= 0) {
        uncutStaleWrites.incrementAndGet();
      }

      assertThat(uncutStaleWrites.get()).isEqualTo(0);
    }
  }

  /** A chunk whose first two bytes carry {@code id}, so the fake can tell chunks apart. */
  private static byte[] chunk(int id, int size) {
    byte[] pcm = new byte[size];
    pcm[0] = (byte) id;
    pcm[1] = (byte) (id >> 8);
    return pcm;
  }

  /** The calls after the last {@code call}, e.g. what followed the last write. */
  private static List<String> afterLast(List<String> calls, String call) {
    List<String> snapshot = new ArrayList<>(calls);
    return snapshot.subList(snapshot.lastIndexOf(call) + 1, snapshot.size());
  }
}
