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
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.kt.examples.live.AudioSupportJava.AudioDeviceException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.sound.sampled.LineUnavailableException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link AudioRecorderJava} against a fake line, so no audio device is needed. */
@RunWith(JUnit4.class)
public final class AudioRecorderJavaTest {
  private static final String MICROPHONE_THREAD = "adk-example-microphone";
  // Long enough for an open on the fake line, short enough that a broken test fails fast.
  private static final Duration TEST_OPEN_TIMEOUT = Duration.ofSeconds(1);

  private final List<Throwable> errors = new CopyOnWriteArrayList<>();
  private final CountDownLatch firstError = new CountDownLatch(1);
  private final AtomicInteger endedCount = new AtomicInteger();
  private final List<String> warnings = new CopyOnWriteArrayList<>();
  private final Logger logger = Logger.getLogger(AudioRecorderJava.class.getName());
  private final Handler warningCapture =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          warnings.add(new SimpleFormatter().formatMessage(record));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  private void report(Throwable error) {
    errors.add(error);
    firstError.countDown();
  }

  @Before
  public void captureWarnings() {
    logger.addHandler(warningCapture);
  }

  @After
  public void releaseWarnings() {
    logger.removeHandler(warningCapture);
  }

  @Test
  public void mimeType_isInputPcmRate() {
    assertThat(new AudioRecorderJava(info -> new FakeDataLineJava(), TEST_OPEN_TIMEOUT).mimeType())
        .isEqualTo("audio/pcm;rate=16000");
  }

  @Test
  public void start_fullAndShortReads_emitsEachReadThenEndsOnAnEmptyReadWithAWarning()
      throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    Collections.addAll(line.reads, 640, 640, 100, 0);
    AudioRecorderJava recorder = new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT);
    List<byte[]> frames = new CopyOnWriteArrayList<>();

    recorder.start(frames::add, this::report, endedCount::incrementAndGet);

    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertThat(frames).hasSize(3);
    assertThat(frames.get(0)).hasLength(640);
    // The fake stamps each read with its index; a reused buffer would show 3 here.
    assertThat(frames.get(1)[0]).isEqualTo((byte) 2);
    assertThat(frames.get(2)).hasLength(100);
    assertThat(errors).isEmpty();
    assertThat(warnings).containsExactly("Microphone read returned 0; recording ended.");
    assertThat(endedCount.get()).isEqualTo(1);
  }

  @Test
  public void start_opensLineWithTenFrameBuffer() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.reads.add(0);

    new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT)
        .start(frame -> {}, this::report, endedCount::incrementAndGet);

    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertWithMessage("opened format: %s", line.openedFormat)
        .that(line.openedFormat.matches(javaSoundFormat(16_000)))
        .isTrue();
    assertThat(line.openedBufferSize).isEqualTo(6_400);
  }

  @Test
  public void start_readsOneBufferPerFrame() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    Collections.addAll(line.reads, 640, 0);

    new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT)
        .start(frame -> {}, this::report, endedCount::incrementAndGet);

    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertThat(line.readLengths).containsExactly(640, 640);
  }

  @Test
  public void stop_beforeStart_neverOpensTheMicrophone() throws Exception {
    AtomicInteger opens = new AtomicInteger();
    AudioRecorderJava recorder =
        new AudioRecorderJava(
            info -> {
              opens.incrementAndGet();
              return new FakeDataLineJava();
            },
            TEST_OPEN_TIMEOUT);
    Set<Thread> before = microphoneThreads();

    recorder.stop();
    recorder.start(frame -> {}, this::report, endedCount::incrementAndGet);

    // Stopped before start, the reader returns before it starts any open.
    awaitMicrophoneThreadsEnded(before);
    assertThat(opens.get()).isEqualTo(0);
    assertThat(errors).isEmpty();
    assertThat(endedCount.get()).isEqualTo(0);
  }

  @Test
  public void stop_twiceWhileReading_stopsAndClosesTheLineWithoutAWarning() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    AudioRecorderJava recorder = new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT);
    recorder.start(frame -> {}, this::report, endedCount::incrementAndGet);
    try {
      awaitFirstRead(line);

      recorder.stop();
      recorder.stop();

      assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      assertThat(line.calls).contains("stop");
      assertThat(errors).isEmpty();
      assertThat(warnings).isEmpty();
      assertThat(endedCount.get()).isEqualTo(0);
    } finally {
      recorder.stop();
    }
  }

  @Test
  public void stop_whileReading_dropsTheBytesTheWokenReadReturns() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.stopRead = 100;
    AudioRecorderJava recorder = new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT);
    List<byte[]> frames = new CopyOnWriteArrayList<>();
    recorder.start(frames::add, this::report, endedCount::incrementAndGet);
    try {
      awaitFirstRead(line);

      recorder.stop();

      assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      assertThat(frames).isEmpty();
      assertThat(errors).isEmpty();
    } finally {
      recorder.stop();
    }
  }

  @Test
  public void stop_lineStopThrows_stillEndsAndClosesTheLine() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.stopThrows = true;
    AudioRecorderJava recorder = new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT);
    recorder.start(frame -> {}, this::report, endedCount::incrementAndGet);
    try {
      awaitFirstRead(line);

      recorder.stop();

      assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      assertThat(errors).isEmpty();
    } finally {
      recorder.stop();
    }
  }

  @Test
  public void start_lineStartFails_closesTheLineAndReportsIt() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.startThrows = true;

    new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT)
        .start(frame -> {}, this::report, endedCount::incrementAndGet);

    assertThat(firstError.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertThat(errors.get(0)).isInstanceOf(IllegalStateException.class);
    assertThat(line.calls).contains("close");
  }

  @Test
  public void start_lineThatFailsToOpen_reportsAudioDeviceException() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    line.openFailure = new LineUnavailableException("busy");
    AudioRecorderJava recorder = new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT);

    recorder.start(frame -> {}, this::report, endedCount::incrementAndGet);

    assertThat(firstError.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertThat(errors.get(0)).isInstanceOf(AudioDeviceException.class);
    assertThat(errors.get(0))
        .hasMessageThat()
        .isEqualTo("cannot open the audio input device for audio/pcm;rate=16000");
    assertThat(endedCount.get()).isEqualTo(0);
  }

  @Test
  public void start_readFails_reportsTheErrorWithoutEnded() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    IllegalStateException failure = new IllegalStateException("read failed");
    line.readFailure = failure;

    new AudioRecorderJava(info -> line, TEST_OPEN_TIMEOUT)
        .start(frame -> {}, this::report, endedCount::incrementAndGet);

    // The line closes before onError runs, so wait for the error itself.
    assertThat(firstError.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertThat(errors).containsExactly(failure);
    assertThat(line.calls).contains("close");
    assertThat(endedCount.get()).isEqualTo(0);
  }

  @Test
  public void stop_duringOpen_endsPromptlyWithoutWaitingOutTheTimeout() throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch opening = new CountDownLatch(1);
    CountDownLatch releaseOpen = new CountDownLatch(1);
    AudioRecorderJava recorder =
        new AudioRecorderJava(
            info -> {
              opening.countDown();
              awaitQuietly(releaseOpen);
              return line;
            },
            Duration.ofSeconds(30));
    Set<Thread> before = microphoneThreads();
    recorder.start(frame -> {}, this::report, endedCount::incrementAndGet);
    try {
      assertWithMessage("the open never started")
          .that(opening.await(LONG_WAIT_SECONDS, SECONDS))
          .isTrue();

      recorder.stop();

      // The open waits up to 30 s, so ending within the join timeout shows stop() cut it short.
      awaitMicrophoneThreadsEnded(before);
    } finally {
      recorder.stop();
      releaseOpen.countDown();
    }
    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
    assertThat(errors).isEmpty();
  }

  @Test
  public void stop_asTheOpenCompletes_closesTheLineOnce() throws Exception {
    List<FakeDataLineJava> lines = new CopyOnWriteArrayList<>();
    CountDownLatch allOpened = new CountDownLatch(200);
    ExecutorService stoppers = Executors.newCachedThreadPool();
    try {
      // Stop on another thread during the open, so it lands before, during or after the handover.
      for (int i = 0; i < 200; i++) {
        AtomicReference<AudioRecorderJava> recorder = new AtomicReference<>();
        recorder.set(
            new AudioRecorderJava(
                info -> openWhileStopping(recorder.get(), stoppers, lines, allOpened),
                TEST_OPEN_TIMEOUT));
        recorder.get().start(frame -> {}, this::report, endedCount::incrementAndGet);
      }

      assertThat(allOpened.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      for (FakeDataLineJava line : lines) {
        assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
      }
      for (FakeDataLineJava line : lines) {
        assertThat(Collections.frequency(line.calls, "close")).isEqualTo(1);
      }
      assertThat(errors).isEmpty();
      assertThat(endedCount.get()).isEqualTo(0);
    } finally {
      stoppers.shutdownNow();
    }
  }

  /** Starts {@code recorder.stop()} on another thread and returns a new line. */
  private static FakeDataLineJava openWhileStopping(
      AudioRecorderJava recorder,
      Executor stoppers,
      List<FakeDataLineJava> lines,
      CountDownLatch opened) {
    FakeDataLineJava line = new FakeDataLineJava();
    lines.add(line);
    stoppers.execute(recorder::stop);
    opened.countDown();
    return line;
  }

  /** Waits for the reader to block in a read. */
  private static void awaitFirstRead(FakeDataLineJava line) throws InterruptedException {
    assertWithMessage("the reader never reached read()")
        .that(line.readEntered.await(LONG_WAIT_SECONDS, SECONDS))
        .isTrue();
  }

  private static Set<Thread> microphoneThreads() {
    Set<Thread> threads = new HashSet<>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals(MICROPHONE_THREAD)) {
        threads.add(thread);
      }
    }
    return threads;
  }

  /** Waits until no microphone thread is running except those in {@code before}. */
  private static void awaitMicrophoneThreadsEnded(Set<Thread> before) throws InterruptedException {
    while (true) {
      Set<Thread> running = microphoneThreads();
      running.removeAll(before);
      if (running.isEmpty()) {
        return;
      }
      for (Thread thread : running) {
        assertThat(thread.join(Duration.ofSeconds(LONG_WAIT_SECONDS))).isTrue();
      }
    }
  }
}
