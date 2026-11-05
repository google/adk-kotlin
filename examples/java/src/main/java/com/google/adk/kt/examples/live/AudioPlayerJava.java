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

import static com.google.adk.kt.examples.live.AudioSupportJava.BYTES_PER_SAMPLE;
import static com.google.adk.kt.examples.live.AudioSupportJava.OPEN_TIMEOUT;
import static com.google.adk.kt.examples.live.AudioSupportJava.lineBufferSizeBytes;
import static com.google.adk.kt.examples.live.AudioSupportJava.newDaemon;
import static com.google.adk.kt.examples.live.AudioSupportJava.openLine;

import com.google.adk.kt.examples.live.AudioSupportJava.AudioDeviceException;
import com.google.adk.kt.examples.live.AudioSupportJava.LineSource;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays 24 kHz mono 16-bit little-endian PCM. {@link #play} only queues a chunk, and a daemon
 * thread writes the queue to the speaker, so the caller never waits on the device. Safe to use from
 * several threads.
 */
final class AudioPlayerJava implements AutoCloseable {
  private static final int SAMPLE_RATE_HZ = 24_000;
  // Bounds how long close() waits for queued speech to play out.
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

  /** A queued chunk and the discard generation it was queued in. */
  private static final class Chunk {
    final byte[] pcm;
    final long generation;

    Chunk(byte[] pcm, long generation) {
      this.pcm = pcm;
      this.generation = generation;
    }
  }

  /** Marks the end of the queue for the writer, put there by {@link #close}. */
  private static final Chunk END = new Chunk(new byte[0], -1);

  private final SourceDataLine line;
  private final Duration drainTimeout;
  private final Consumer<Throwable> onError;
  private final Object lock = new Object();
  private final LinkedBlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
  // Bumped by discardQueuedAudio(); a chunk from an older generation stops at its next slice.
  private final AtomicLong generation = new AtomicLong();
  private final Thread writer;
  // Guarded by lock.
  private boolean closed;
  // Set when close() stops waiting; the writer ends after its current slice and drops later errors.
  private volatile boolean cutOff;

  /**
   * Opens the default speaker, waiting up to {@code OPEN_TIMEOUT}. A failure to write later goes to
   * {@code onError} and stops playback, and {@link #close} plays out queued audio for at most
   * {@code DRAIN_TIMEOUT}.
   *
   * @throws AudioDeviceException if no speaker opens in time or the platform refuses it.
   */
  static AudioPlayerJava open(Consumer<Throwable> onError) {
    return new AudioPlayerJava(
        info -> (SourceDataLine) AudioSystem.getLine(info), OPEN_TIMEOUT, DRAIN_TIMEOUT, onError);
  }

  AudioPlayerJava(
      LineSource<SourceDataLine> lineSource,
      Duration openTimeout,
      Duration drainTimeout,
      Consumer<Throwable> onError) {
    this.line =
        openLine(
            SourceDataLine.class,
            SAMPLE_RATE_HZ,
            lineSource,
            openTimeout,
            new CompletableFuture<>());
    this.drainTimeout = drainTimeout;
    this.onError = onError;
    writer = newDaemon(this::writeQueue, "adk-example-speaker");
    writer.start();
  }

  /**
   * Queues {@code pcm}, which must hold whole 16-bit samples, to play after the chunks already
   * queued.
   *
   * @throws IllegalArgumentException if {@code pcm} ends in a partial sample.
   * @throws IllegalStateException if the player is closed.
   */
  void play(byte[] pcm) {
    if (pcm.length % BYTES_PER_SAMPLE != 0) {
      throw new IllegalArgumentException(
          "pcm must be whole 16-bit samples, was " + pcm.length + " bytes");
    }
    synchronized (lock) {
      if (closed) {
        throw new IllegalStateException("player is closed");
      }
      // Stamped under the lock, so a discard either drops this chunk or comes after it.
      queue.add(new Chunk(pcm.clone(), generation.get()));
    }
  }

  /**
   * Drops the queued chunks, the rest of the chunk being written, and what the speaker still holds;
   * does nothing after close.
   */
  void discardQueuedAudio() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      queue.clear();
      generation.incrementAndGet();
      line.flush();
    }
  }

  /**
   * Plays out what is queued for up to the drain timeout, then drops the rest and closes the
   * speaker. Only the first call does anything; an interrupt cuts the play-out short and stays set.
   */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      queue.add(END);
    }
    // On the writer thread (from onError) there is nothing left to wait for.
    boolean drained = Thread.currentThread() != writer && writerEndsWithin(drainTimeout);
    try {
      if (!drained) {
        cutOff = true;
        // The writer stops at its next slice check; flush() releases a write blocked on the device.
        line.flush();
      }
    } finally {
      line.close();
    }
  }

  /** Returns whether the writer ends within {@code timeout}; an interrupt stays set. */
  private boolean writerEndsWithin(Duration timeout) {
    try {
      return writer.join(timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void writeQueue() {
    try {
      for (Chunk chunk = queue.take(); chunk != END && !cutOff; chunk = queue.take()) {
        write(chunk);
      }
      // Drain here, not in close(), so the drain timeout covers it too.
      if (!cutOff) {
        line.drain();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException | Error e) {
      // Any failure, fatal ones too, must end the run rather than silently stop the speaker.
      if (!cutOff) {
        onError.accept(e);
      }
    }
  }

  /**
   * Writes {@code chunk} in device-buffer slices until a discard or a cut-off close stops it. A
   * short write that no discard explains throws a device failure, which writeQueue drops after a
   * cut-off.
   */
  private void write(Chunk chunk) {
    int slice = lineBufferSizeBytes(SAMPLE_RATE_HZ);
    for (int offset = 0;
        offset < chunk.pcm.length && !cutOff && generation.get() == chunk.generation;
        offset += slice) {
      int len = Math.min(slice, chunk.pcm.length - offset);
      int written = line.write(chunk.pcm, offset, len);
      // write() clears Java Sound's flush flag, so flush again if a discard raced in.
      if (!cutOff && generation.get() != chunk.generation) {
        line.flush();
      } else if (written < len) {
        // Java Sound reports a device error, such as a lost speaker, as a short write.
        throw new AudioDeviceException(
            "the audio output device took " + written + " of " + len + " bytes", null);
      }
    }
  }
}
