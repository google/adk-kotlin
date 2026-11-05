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

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Thrown when a platform audio device cannot be opened. */
internal class AudioDeviceException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

private const val CHANNEL_COUNT: Int = 1

/** Bytes per sample in signed 16-bit PCM. */
internal const val BYTES_PER_SAMPLE: Int = 2

// 20 ms frames keep latency low without flooding the connection.
private const val FRAMES_PER_SECOND: Int = 50

// Frames a device buffer holds, trading latency against over- and underruns.
private const val DEVICE_BUFFER_FRAMES: Int = 10

/** Bytes in one 20 ms frame at [sampleRateHz], rounded down to whole samples. */
internal fun bytesPerFrame(sampleRateHz: Int): Int =
  sampleRateHz / FRAMES_PER_SECOND * CHANNEL_COUNT * BYTES_PER_SAMPLE

/** Bytes a Java Sound line is asked to buffer at [sampleRateHz]. */
internal fun lineBufferSizeBytes(sampleRateHz: Int): Int =
  bytesPerFrame(sampleRateHz) * DEVICE_BUFFER_FRAMES

/** MIME type of a single-channel 16-bit PCM stream at [sampleRateHz]. */
internal fun pcmMimeType(sampleRateHz: Int): String = "audio/pcm;rate=$sampleRateHz"

/** The signed 16-bit, little-endian, mono [AudioFormat] for [sampleRateHz]. */
internal fun javaSoundFormat(sampleRateHz: Int): AudioFormat =
  AudioFormat(
    sampleRateHz.toFloat(),
    BYTES_PER_SAMPLE * Byte.SIZE_BITS,
    CHANNEL_COUNT,
    /* signed= */ true,
    /* bigEndian= */ false,
  )

/** How long to wait for a device to open; on some hosts the native open hangs. */
private val DEVICE_OPEN_TIMEOUT: Duration = 5.seconds

/**
 * Runs [open] on a daemon thread and returns the line it opens, waiting at most [timeout], or
 * throws [AudioDeviceException] naming [direction] and [mimeType] when the open times out or the
 * platform refuses the device. A line that opens after the wait, or whose caller is interrupted, is
 * closed.
 */
internal fun <T : AutoCloseable> openDeviceLine(
  direction: String,
  mimeType: String,
  timeout: Duration = DEVICE_OPEN_TIMEOUT,
  open: () -> T,
): T {
  val opened = CompletableFuture<T>()
  // A daemon, so a native open that never returns cannot keep the JVM alive.
  thread(isDaemon = true, name = "adk-example-audio-open") {
    try {
      val line = open()
      if (!opened.complete(line)) line.close()
    } catch (e: Throwable) {
      opened.completeExceptionally(e)
    }
  }
  val cannotOpen = "cannot open the audio $direction device for $mimeType"
  // Cancel so a line opening later closes itself; one that opened just now is closed here.
  fun discardLateLine() {
    if (!opened.cancel(false)) runCatching { opened.getNow(null)?.close() }
  }
  return try {
    opened.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  } catch (e: TimeoutException) {
    discardLateLine()
    throw AudioDeviceException("$cannotOpen (timed out after $timeout)", e)
  } catch (e: ExecutionException) {
    // The open itself failed, so there is no line to discard.
    throw when (val cause = e.cause) {
      is LineUnavailableException,
      is IllegalArgumentException,
      is SecurityException -> AudioDeviceException(cannotOpen, cause)
      else -> cause ?: e
    }
  } catch (e: InterruptedException) {
    discardLateLine()
    throw e
  }
}

/**
 * Gets a [lineClass] line for [sampleRateHz] audio from [getLine] and has [configure] open and
 * start it within the [openDeviceLine] timeout, since that native open is what can hang. A
 * [configure] failure closes the line before rethrowing.
 */
internal fun <T : AutoCloseable> openConfiguredLine(
  sampleRateHz: Int,
  lineClass: Class<T>,
  getLine: (DataLine.Info) -> T,
  configure: T.(AudioFormat, Int) -> Unit,
): T {
  val format = javaSoundFormat(sampleRateHz)
  val info = DataLine.Info(lineClass, format)
  val direction = if (SourceDataLine::class.java.isAssignableFrom(lineClass)) "output" else "input"
  return openDeviceLine(direction, pcmMimeType(sampleRateHz)) {
    val line = getLine(info)
    try {
      line.configure(format, lineBufferSizeBytes(sampleRateHz))
    } catch (e: Throwable) {
      try {
        line.close()
      } catch (s: Throwable) {
        e.addSuppressed(s)
      }
      throw e
    }
    line
  }
}
