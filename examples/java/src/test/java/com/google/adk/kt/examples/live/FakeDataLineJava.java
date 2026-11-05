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

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** A line the tests control; it plays the speaker or the microphone as the code asks. */
final class FakeDataLineJava implements SourceDataLine, TargetDataLine {
  static final long LONG_WAIT_SECONDS = 5;

  final List<String> calls = new CopyOnWriteArrayList<>();

  /** The length each read asked for. */
  final List<Integer> readLengths = new CopyOnWriteArrayList<>();

  /** Results the microphone's reads return in turn; a read waits when none is left. */
  final LinkedBlockingQueue<Integer> reads = new LinkedBlockingQueue<>();

  /** What the read that {@code stop} wakes returns; Java Sound returns the bytes read so far. */
  volatile int stopRead;

  /** Counts down when the code under test closes the line. */
  final CountDownLatch closed = new CountDownLatch(1);

  /** Counts down on the first read. */
  final CountDownLatch readEntered = new CountDownLatch(1);

  /** When set, {@code open} waits on it, standing in for a native open that hangs. */
  volatile CountDownLatch openGate;

  volatile LineUnavailableException openFailure;
  volatile RuntimeException readFailure;
  volatile int openedBufferSize;
  volatile AudioFormat openedFormat;
  volatile boolean startThrows;
  volatile boolean stopThrows;
  private volatile boolean open = true;

  private int readCount;

  @Override
  public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
    calls.add("open");
    CountDownLatch gate = openGate;
    if (gate != null) {
      awaitQuietly(gate);
    }
    if (openFailure != null) {
      throw openFailure;
    }
    openedBufferSize = bufferSize;
    openedFormat = format;
  }

  @Override
  public void open(AudioFormat format) throws LineUnavailableException {
    open(format, 0);
  }

  @Override
  public void open() throws LineUnavailableException {
    open(null, 0);
  }

  @Override
  public int write(byte[] b, int off, int len) {
    throw new UnsupportedOperationException("a microphone line");
  }

  @Override
  public int read(byte[] b, int off, int len) {
    readEntered.countDown();
    readLengths.add(len);
    if (readFailure != null) {
      throw readFailure;
    }
    int bytesRead = takeRead();
    if (bytesRead > 0) {
      b[off] = (byte) ++readCount;
    }
    return bytesRead;
  }

  /** Waits for the next read result, ignoring interrupts as Java Sound's read does. */
  private int takeRead() {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return reads.take();
        } catch (InterruptedException e) {
          // Only stop(), which queues a 0, unblocks the read; the interrupt is restored after.
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void flush() {
    calls.add("flush");
  }

  @Override
  public void stop() {
    calls.add("stop");
    // A stopped microphone's blocked read returns, as Java Sound's does.
    reads.add(stopRead);
    if (stopThrows) {
      throw new IllegalStateException("stop failed");
    }
  }

  @Override
  public void close() {
    calls.add("close");
    open = false;
    closed.countDown();
  }

  @Override
  public void start() {
    calls.add("start");
    if (startThrows) {
      throw new IllegalStateException("start failed");
    }
  }

  @Override
  public void drain() {
    calls.add("drain");
  }

  @Override
  public boolean isRunning() {
    return false;
  }

  @Override
  public boolean isActive() {
    return false;
  }

  @Override
  public AudioFormat getFormat() {
    return openedFormat;
  }

  @Override
  public int getBufferSize() {
    return openedBufferSize;
  }

  @Override
  public int available() {
    return 0;
  }

  @Override
  public int getFramePosition() {
    return 0;
  }

  @Override
  public long getLongFramePosition() {
    return 0;
  }

  @Override
  public long getMicrosecondPosition() {
    return 0;
  }

  @Override
  public float getLevel() {
    return 0;
  }

  @Override
  public Line.Info getLineInfo() {
    return null;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public Control[] getControls() {
    return new Control[0];
  }

  @Override
  public boolean isControlSupported(Control.Type control) {
    return false;
  }

  @Override
  public Control getControl(Control.Type control) {
    throw new IllegalArgumentException("no controls");
  }

  @Override
  public void addLineListener(LineListener listener) {}

  @Override
  public void removeLineListener(LineListener listener) {}

  /** Waits up to {@link #LONG_WAIT_SECONDS} for {@code latch}, keeping an interrupt set. */
  static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(LONG_WAIT_SECONDS, SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
