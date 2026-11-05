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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * What microphone capture and speaker playback share: the PCM formats, the device error and a
 * bounded open.
 */
final class AudioSupportJava {

  // 20 ms frames keep latency low without flooding the connection.
  private static final int FRAMES_PER_SECOND = 50;
  static final int BYTES_PER_SAMPLE = 2;
  // Frames a device buffer holds, trading latency against over- and underruns.
  private static final int DEVICE_BUFFER_FRAMES = 10;
  // How long an open may take before the device counts as missing.
  static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);

  private static final Executor opener =
      Executors.newCachedThreadPool(task -> newDaemon(task, "adk-example-audio-open"));

  /** Thrown when a microphone or speaker cannot be opened. */
  static final class AudioDeviceException extends RuntimeException {
    AudioDeviceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Gets an unopened line for a format, such as {@link AudioSystem#getLine}. */
  @FunctionalInterface
  interface LineSource<T extends DataLine> {
    T get(DataLine.Info info) throws LineUnavailableException;
  }

  static String mimeType(int sampleRateHz) {
    return "audio/pcm;rate=" + sampleRateHz;
  }

  static int bytesPerFrame(int sampleRateHz) {
    return sampleRateHz / FRAMES_PER_SECOND * BYTES_PER_SAMPLE;
  }

  /** Bytes a line is asked to buffer at {@code sampleRateHz}. */
  static int lineBufferSizeBytes(int sampleRateHz) {
    return bytesPerFrame(sampleRateHz) * DEVICE_BUFFER_FRAMES;
  }

  static AudioFormat javaSoundFormat(int sampleRateHz) {
    return new AudioFormat(
        sampleRateHz,
        BYTES_PER_SAMPLE * Byte.SIZE,
        /* channels= */ 1,
        /* signed= */ true,
        /* bigEndian= */ false);
  }

  /** Formats {@code timeout} as {@code Ns} for whole seconds, else as {@code Nms}. */
  static String formatDuration(Duration timeout) {
    long millis = timeout.toMillis();
    return millis % 1_000 == 0 ? millis / 1_000 + "s" : millis + "ms";
  }

  static Thread newDaemon(Runnable task, String name) {
    Thread thread = new Thread(task, name);
    // Daemon: a call stuck in the audio driver must not keep the JVM up.
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Opens and starts a line on a daemon thread, waiting at most {@code timeout}, since some hosts
   * hang in the native open. The caller may cancel {@code opened} to give up; a line that opens too
   * late is closed, and a timeout, a cancel, an interrupt or a refused device throws an {@link
   * AudioDeviceException}.
   */
  static <T extends DataLine> T openLine(
      Class<T> type,
      int sampleRateHz,
      LineSource<? extends T> source,
      Duration timeout,
      CompletableFuture<T> opened) {
    AudioFormat format = javaSoundFormat(sampleRateHz);
    int bufferBytes = lineBufferSizeBytes(sampleRateHz);
    opener.execute(() -> openInto(opened, source, type, format, bufferBytes));
    String direction = SourceDataLine.class.isAssignableFrom(type) ? "output" : "input";
    String cannotOpen =
        "cannot open the audio " + direction + " device for " + mimeType(sampleRateHz);
    try {
      return opened.get(timeout.toMillis(), MILLISECONDS);
    } catch (TimeoutException e) {
      discardLateLine(opened, e);
      throw new AudioDeviceException(
          cannotOpen + " (timed out after " + formatDuration(timeout) + ")", e);
    } catch (CancellationException e) {
      // Cancelled by a caller that gave up; a line that still arrives is closed.
      throw new AudioDeviceException(cannotOpen + " (cancelled)", e);
    } catch (InterruptedException e) {
      discardLateLine(opened, e);
      Thread.currentThread().interrupt();
      throw new AudioDeviceException(cannotOpen + " (interrupted while opening it)", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof LineUnavailableException
          || cause instanceof IllegalArgumentException
          || cause instanceof SecurityException) {
        throw new AudioDeviceException(cannotOpen, cause);
      }
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      // The opener throws nothing checked besides LineUnavailableException.
      throw new AssertionError(cause);
    }
  }

  /** Gets, opens and starts a line and hands it to {@code opened}, or closes it if too late. */
  private static <T extends DataLine> void openInto(
      CompletableFuture<T> opened,
      LineSource<? extends T> source,
      Class<T> type,
      AudioFormat format,
      int bufferBytes) {
    try {
      T line = source.get(new DataLine.Info(type, format));
      try {
        openAndStart(type, line, format, bufferBytes);
      } catch (Throwable e) {
        closeQuietly(line, e);
        throw e;
      }
      if (!opened.complete(line)) {
        line.close();
      }
    } catch (Throwable e) {
      opened.completeExceptionally(e);
    }
  }

  /** Opens {@code line} as the requested {@code type}, since a line could implement both. */
  private static <T extends DataLine> void openAndStart(
      Class<T> type, T line, AudioFormat format, int bufferBytes) throws LineUnavailableException {
    if (SourceDataLine.class.isAssignableFrom(type)) {
      ((SourceDataLine) line).open(format, bufferBytes);
    } else {
      ((TargetDataLine) line).open(format, bufferBytes);
    }
    line.start();
  }

  /** Cancels a pending open so its line closes itself, or closes one that opened just now. */
  private static void discardLateLine(CompletableFuture<? extends Line> opened, Throwable primary) {
    // A failed or cancelled open left no line to close.
    if (!opened.cancel(false) && !opened.isCompletedExceptionally()) {
      closeQuietly(opened.join(), primary);
    }
  }

  private static void closeQuietly(Line line, Throwable primary) {
    try {
      line.close();
    } catch (RuntimeException | Error e) {
      primary.addSuppressed(e);
    }
  }

  private AudioSupportJava() {}
}
