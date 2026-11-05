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

import static com.google.adk.kt.examples.live.AudioSupportJava.OPEN_TIMEOUT;
import static com.google.adk.kt.examples.live.AudioSupportJava.bytesPerFrame;
import static com.google.adk.kt.examples.live.AudioSupportJava.newDaemon;
import static com.google.adk.kt.examples.live.AudioSupportJava.openLine;

import com.google.adk.kt.examples.live.AudioSupportJava.LineSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;

/**
 * A microphone recorder that captures 16 kHz mono 16-bit little-endian PCM in 20 ms frames. Nothing
 * opens until {@link #start}, which opens and reads the microphone on its own daemon thread. {@link
 * #stop} may be called from any thread.
 */
final class AudioRecorderJava {
  private static final int SAMPLE_RATE_HZ = 16_000;

  private static final Logger logger = Logger.getLogger(AudioRecorderJava.class.getName());

  private final LineSource<TargetDataLine> lineSource;
  private final Duration openTimeout;
  private final Object lock = new Object();

  /** Set by {@link #stop}; written under lock, read without it by the reader thread. */
  private volatile boolean stopped;

  /** The open in progress, so {@link #stop} can cancel its wait; guarded by lock. */
  private CompletableFuture<TargetDataLine> opening;

  /** The open line, so {@link #stop} can unblock its read; guarded by lock. */
  private TargetDataLine line;

  AudioRecorderJava() {
    this(info -> (TargetDataLine) AudioSystem.getLine(info), OPEN_TIMEOUT);
  }

  AudioRecorderJava(LineSource<TargetDataLine> lineSource, Duration openTimeout) {
    this.lineSource = lineSource;
    this.openTimeout = openTimeout;
  }

  String mimeType() {
    return AudioSupportJava.mimeType(SAMPLE_RATE_HZ);
  }

  /**
   * Opens the microphone within the open timeout given to the constructor and passes each frame to
   * {@code onFrame}, both on a daemon thread. If the device stops on its own, {@code onEnded} runs;
   * any failure before {@link #stop} goes to {@code onError}. Call at most once.
   */
  void start(Consumer<byte[]> onFrame, Consumer<Throwable> onError, Runnable onEnded) {
    newDaemon(() -> record(onFrame, onError, onEnded), "adk-example-microphone").start();
  }

  /** Ends the recording and closes the microphone; safe to call at any time, more than once. */
  void stop() {
    CompletableFuture<TargetDataLine> pending;
    TargetDataLine current;
    synchronized (lock) {
      stopped = true;
      pending = opening;
      current = line;
    }
    if (pending != null) {
      // Ends a wait for the open at once, rather than after its timeout.
      pending.cancel(false);
    }
    if (current != null) {
      try {
        // Java Sound's read ignores interrupts; stopping the line unblocks it.
        current.stop();
      } catch (RuntimeException | Error e) {
        // The reader still closes the line, and the caller's cleanup must go on.
        logger.log(Level.FINE, "Microphone stop failed: {0}", e.getClass().getSimpleName());
      }
    }
  }

  private void record(Consumer<byte[]> onFrame, Consumer<Throwable> onError, Runnable onEnded) {
    CompletableFuture<TargetDataLine> pending = new CompletableFuture<>();
    synchronized (lock) {
      if (stopped) {
        return;
      }
      opening = pending;
    }
    try (TargetDataLine opened =
        openLine(TargetDataLine.class, SAMPLE_RATE_HZ, lineSource, openTimeout, pending)) {
      synchronized (lock) {
        if (stopped) {
          return;
        }
        line = opened;
      }
      byte[] frame = new byte[bytesPerFrame(SAMPLE_RATE_HZ)];
      int read = 0;
      while (!stopped) {
        read = opened.read(frame, 0, frame.length);
        // stop() wakes a blocked read with the bytes it has so far; drop them.
        if (read <= 0 || stopped) {
          break;
        }
        onFrame.accept(Arrays.copyOf(frame, read));
      }
      // Reaching here without stop() means the device stopped on its own.
      if (!stopped) {
        logger.log(Level.WARNING, "Microphone read returned {0}; recording ended.", read);
        onEnded.run();
      }
    } catch (RuntimeException | Error e) {
      // Any failure, fatal ones too, must end the run rather than silently lose the microphone.
      if (!stopped) {
        onError.accept(e);
      }
    }
  }
}
