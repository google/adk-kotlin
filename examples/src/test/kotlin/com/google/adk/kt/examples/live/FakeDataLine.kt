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

import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * A Java Sound line that records the calls made on it, so recorder tests need no device.
 *
 * [read] serves [reads] in order and blocks until one arrives.
 */
internal class FakeDataLine : SourceDataLine, TargetDataLine {
  val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val reads = LinkedBlockingQueue<ByteArray>()
  val readLengths: MutableList<Int> = Collections.synchronizedList(mutableListOf())
  val readStarted = Semaphore(0)
  @Volatile var startThrows = false
  @Volatile var stopThrows = false
  var openedFormat: AudioFormat? = null
  var openedBufferSize = -1

  override fun open(format: AudioFormat, bufferSize: Int) {
    calls += "open"
    openedFormat = format
    openedBufferSize = bufferSize
  }

  override fun open(format: AudioFormat) {
    open(format, -1)
  }

  override fun open() {
    open(javaSoundFormat(16_000), -1)
  }

  override fun write(b: ByteArray, off: Int, len: Int): Int =
    throw UnsupportedOperationException("a microphone line")

  /** Copies the next queued chunk into [b]; an empty chunk returns 0, as a stopped line does. */
  override fun read(b: ByteArray, off: Int, len: Int): Int {
    readStarted.release()
    readLengths += len
    // The JDK's read ignores interrupts, so retry take() and restore the flag afterwards.
    var interrupted = false
    var chunk: ByteArray? = null
    while (chunk == null) {
      try {
        chunk = reads.take()
      } catch (e: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
    chunk.copyInto(b, off, 0, minOf(len, chunk.size))
    return minOf(len, chunk.size)
  }

  override fun start() {
    calls += "start"
    if (startThrows) throw IllegalStateException("start failed")
  }

  override fun stop() {
    calls += "stop"
    // A stopped line no longer blocks a read (TargetDataLine.read javadoc).
    reads.offer(ByteArray(0))
    if (stopThrows) throw IllegalStateException("stop failed")
  }

  override fun drain() {
    calls += "drain"
  }

  override fun flush() {
    calls += "flush"
  }

  override fun close() {
    calls += "close"
    reads.offer(ByteArray(0))
  }

  override fun isOpen() = "open" in calls && "close" !in calls

  override fun isRunning() = false

  override fun isActive() = false

  override fun getFormat(): AudioFormat = openedFormat ?: javaSoundFormat(16_000)

  override fun getBufferSize() = openedBufferSize

  override fun available() = 0

  override fun getFramePosition() = 0

  override fun getLongFramePosition() = 0L

  override fun getMicrosecondPosition() = 0L

  override fun getLevel() = 0f

  override fun getLineInfo(): Line.Info = Line.Info(FakeDataLine::class.java)

  override fun getControls(): Array<Control> = emptyArray()

  override fun isControlSupported(control: Control.Type) = false

  override fun getControl(control: Control.Type): Control =
    throw IllegalArgumentException("no controls")

  override fun addLineListener(listener: LineListener) {}

  override fun removeLineListener(listener: LineListener) {}
}
