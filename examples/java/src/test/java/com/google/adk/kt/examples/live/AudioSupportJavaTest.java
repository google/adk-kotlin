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

import static com.google.adk.kt.examples.live.AudioSupportJava.bytesPerFrame;
import static com.google.adk.kt.examples.live.AudioSupportJava.formatDuration;
import static com.google.adk.kt.examples.live.AudioSupportJava.javaSoundFormat;
import static com.google.adk.kt.examples.live.AudioSupportJava.lineBufferSizeBytes;
import static com.google.adk.kt.examples.live.AudioSupportJava.openLine;
import static com.google.adk.kt.examples.live.FakeDataLineJava.LONG_WAIT_SECONDS;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.adk.kt.examples.live.AudioSupportJava.AudioDeviceException;
import com.google.adk.kt.examples.live.AudioSupportJava.LineSource;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link AudioSupportJava}'s formats and bounded open, so no audio device is needed. */
@RunWith(JUnit4.class)
public final class AudioSupportJavaTest {

  @Test
  public void format_inputRate_isLittleEndianMono16BitPcm() {
    AudioFormat format = javaSoundFormat(16_000);

    assertThat(format.getEncoding()).isEqualTo(AudioFormat.Encoding.PCM_SIGNED);
    assertThat(format.getSampleRate()).isEqualTo(16_000f);
    assertThat(format.getSampleSizeInBits()).isEqualTo(16);
    assertThat(format.getChannels()).isEqualTo(1);
    assertThat(format.getFrameSize()).isEqualTo(2);
    assertThat(format.isBigEndian()).isFalse();
  }

  @Test
  public void bytesPerFrame_knownRates_areTwentyMillisecondsOfSamples() {
    assertThat(bytesPerFrame(16_000)).isEqualTo(640);
    assertThat(bytesPerFrame(24_000)).isEqualTo(960);
    // 11025 Hz over 20 ms is 220.5 samples; a frame must not end halfway through one.
    assertThat(bytesPerFrame(11_025)).isEqualTo(440);
  }

  @Test
  public void lineBufferSizeBytes_inputRate_holdsTenFrames() {
    assertThat(lineBufferSizeBytes(16_000)).isEqualTo(6_400);
  }

  @Test
  public void formatDuration_wholeSecondsOrMilliseconds_formatsAsSecondsOrMillis() {
    assertThat(formatDuration(Duration.ofSeconds(5))).isEqualTo("5s");
    assertThat(formatDuration(Duration.ofMillis(50))).isEqualTo("50ms");
  }

  @Test
  public void openLine_lineOpensInTime_returnsItStartedWithTheFormatAndBuffer() {
    FakeDataLineJava line = new FakeDataLineJava();

    TargetDataLine opened =
        openLine(
            TargetDataLine.class,
            16_000,
            info -> line,
            Duration.ofSeconds(1),
            new CompletableFuture<>());

    assertThat(opened).isSameInstanceAs(line);
    assertThat(line.openedFormat.matches(javaSoundFormat(16_000))).isTrue();
    assertThat(line.openedBufferSize).isEqualTo(6_400);
    assertThat(line.calls).containsExactly("open", "start").inOrder();
  }

  @Test
  public void openLine_lineUnavailable_throwsAudioDeviceExceptionAndClosesTheLine() {
    FakeDataLineJava line = new FakeDataLineJava();
    LineUnavailableException refused = new LineUnavailableException("busy");
    line.openFailure = refused;
    CompletableFuture<TargetDataLine> opened = new CompletableFuture<>();

    var thrown =
        assertThrows(
            AudioDeviceException.class,
            () ->
                openLine(
                    TargetDataLine.class, 16_000, info -> line, Duration.ofSeconds(1), opened));

    assertThat(thrown).hasMessageThat().contains("input device for audio/pcm;rate=16000");
    assertThat(thrown).hasCauseThat().isSameInstanceAs(refused);
    assertThat(line.calls).containsExactly("open", "close").inOrder();
  }

  @Test
  public void openLine_startFails_closesTheLineAndRethrows() {
    FakeDataLineJava line = new FakeDataLineJava();
    line.startThrows = true;
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();

    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                openLine(
                    SourceDataLine.class, 24_000, info -> line, Duration.ofSeconds(1), opened));

    assertThat(thrown).hasMessageThat().isEqualTo("start failed");
    assertThat(line.calls).containsExactly("open", "start", "close").inOrder();
  }

  @Test
  public void openLine_cancelledFuture_throwsAudioDeviceExceptionAndClosesTheLine()
      throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();
    opened.cancel(false);

    var thrown =
        assertThrows(
            AudioDeviceException.class,
            () ->
                openLine(
                    SourceDataLine.class, 24_000, info -> line, Duration.ofSeconds(1), opened));

    assertThat(thrown).hasMessageThat().endsWith("(cancelled)");
    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
  }

  @Test
  public void openLine_openThatNeverReturns_throwsAfterTheTimeout() {
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch releaseOpen = new CountDownLatch(1);
    line.openGate = releaseOpen;
    try {
      var thrown =
          assertThrows(
              AudioDeviceException.class,
              () ->
                  openLine(
                      SourceDataLine.class, 24_000, info -> line, Duration.ofMillis(50), opened));

      assertThat(thrown).hasMessageThat().contains("output device for audio/pcm;rate=24000");
      assertThat(thrown).hasMessageThat().contains("(timed out after 50ms)");
    } finally {
      releaseOpen.countDown();
    }
  }

  @Test
  public void openLine_lineOpenedAfterTheTimeout_isClosed() throws Exception {
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch releaseOpen = new CountDownLatch(1);
    line.openGate = releaseOpen;

    try {
      assertThrows(
          AudioDeviceException.class,
          () ->
              openLine(SourceDataLine.class, 24_000, info -> line, Duration.ofMillis(50), opened));
    } finally {
      releaseOpen.countDown();
    }

    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
  }

  @Test
  public void openLine_audioDeviceExceptionFromOpen_isRethrownAsIs() {
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();
    AudioDeviceException refused = new AudioDeviceException("refused", /* cause= */ null);

    LineSource<SourceDataLine> source =
        info -> {
          throw refused;
        };

    var thrown =
        assertThrows(
            AudioDeviceException.class,
            () -> openLine(SourceDataLine.class, 24_000, source, Duration.ofSeconds(1), opened));

    assertThat(thrown).isSameInstanceAs(refused);
  }

  @Test
  public void openLine_illegalArgumentFromOpen_throwsAudioDeviceException() {
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();
    LineSource<SourceDataLine> source =
        info -> {
          throw new IllegalArgumentException("unsupported format");
        };
    var thrown =
        assertThrows(
            AudioDeviceException.class,
            () -> openLine(SourceDataLine.class, 24_000, source, Duration.ofSeconds(1), opened));

    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void openLine_securityExceptionFromOpen_throwsAudioDeviceException() {
    CompletableFuture<SourceDataLine> opened = new CompletableFuture<>();
    LineSource<SourceDataLine> source =
        info -> {
          throw new SecurityException("denied");
        };
    var thrown =
        assertThrows(
            AudioDeviceException.class,
            () -> openLine(SourceDataLine.class, 24_000, source, Duration.ofSeconds(1), opened));

    assertThat(thrown).hasCauseThat().isInstanceOf(SecurityException.class);
  }

  @Test
  public void openLine_interruptedCaller_throwsAudioDeviceExceptionAndClosesTheLateLine()
      throws Exception {
    FakeDataLineJava line = new FakeDataLineJava();
    CountDownLatch releaseOpen = new CountDownLatch(1);
    line.openGate = releaseOpen;
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean stillInterrupted = new AtomicBoolean();
    Thread caller = new Thread(() -> openCatchingFailure(line, thrown, stillInterrupted));

    boolean finished;
    try {
      caller.start();
      caller.interrupt();
      finished = caller.join(Duration.ofSeconds(2));
    } finally {
      releaseOpen.countDown();
    }

    assertThat(finished).isTrue();
    // An interrupt is reported as a device failure, and the thread stays interrupted.
    assertThat(thrown.get()).isInstanceOf(AudioDeviceException.class);
    assertThat(thrown.get()).hasMessageThat().contains("(interrupted while opening it)");
    assertThat(stillInterrupted.get()).isTrue();
    assertThat(line.closed.await(LONG_WAIT_SECONDS, SECONDS)).isTrue();
  }

  /** Opens {@code line}, recording what the open throws and whether the interrupt flag survived. */
  private static void openCatchingFailure(
      FakeDataLineJava line, AtomicReference<Throwable> thrown, AtomicBoolean stillInterrupted) {
    try {
      var unused =
          openLine(
              SourceDataLine.class,
              24_000,
              info -> line,
              Duration.ofSeconds(5),
              new CompletableFuture<>());
    } catch (Throwable e) {
      thrown.set(e);
      stillInterrupted.set(Thread.currentThread().isInterrupted());
    }
  }
}
