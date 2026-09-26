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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.models.CONCURRENT_COLLECTION_MESSAGE
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.singleCollectorFlow
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a [LiveConnection]. Override the `...Async` methods, which
 * return a [CompletableFuture], and [receiveJava], which returns a [Publisher] of one model turn;
 * the base drops audio from history, validates sent content, allows one collector of [receive] at a
 * time, and calls [closeSessionAsync] at most once. Return a new future or publisher from each
 * call, promptly, with any blocking work inside it, and fail a future rather than cancel it.
 */
@AdkJavaInteropApi
abstract class BaseFutureLiveConnection : LiveConnection {

  private val collecting = Mutex()
  private val closeStarted = AtomicBoolean()
  private val closeOutcome = CompletableFuture<Void?>()

  final override suspend fun sendHistory(history: List<Content>) {
    // Drops audio, as LiveConnection.sendHistory requires, and sends nothing if nothing is left.
    val contents = history.map { it.withoutAudioParts() }.filter { it.parts.isNotEmpty() }
    if (contents.isNotEmpty()) awaitHook("sendHistoryAsync") { sendHistoryAsync(contents) }
  }

  final override suspend fun sendContent(content: Content, partial: Boolean) {
    // Rejects invalid content, as LiveConnection.sendContent requires, before the hook runs.
    val input = ContentInput(content, partial)
    awaitHook("sendContentAsync") { sendContentAsync(input.content, input.partial) }
  }

  final override suspend fun sendRealtime(input: RealtimeInput) {
    awaitHook("sendRealtimeAsync") { sendRealtimeAsync(input) }
  }

  // Requests one response at a time, so a collection that stops early loses at most one.
  final override fun receive(): Flow<LlmResponse> =
    singleCollectorFlow(collecting, CONCURRENT_COLLECTION_MESSAGE) {
        receiveJava().asFlow().buffer(Channel.RENDEZVOUS)
      }
      // Reports a cancellation from receiveJava or its publisher as a failure, as awaitHook does.
      .catch { e ->
        if (e is CancellationException && currentCoroutineContext().isActive) {
          throw IllegalStateException(
            "The call to receiveJava or its publisher was cancelled; fail the publisher instead",
            e,
          )
        }
        throw e
      }

  final override suspend fun closeSession() {
    if (closeStarted.compareAndSet(false, true)) {
      try {
        closeSessionAsync().whenComplete { _, error ->
          if (error == null) {
            closeOutcome.complete(null)
          } else {
            closeOutcome.completeExceptionally(error)
          }
        }
      } catch (e: Throwable) {
        closeOutcome.completeExceptionally(e)
      }
      // Awaits a copy, so a cancelled caller cannot cancel what later callers wait on.
      awaitHook("closeSessionAsync") { closeOutcome.thenApply { it } }
    } else {
      // Waits for the close in flight, leaving its failure to the first caller.
      closeOutcome.exceptionally { null }.await()
    }
  }

  /**
   * Blocks until the close finishes, so never call it from a thread that the future from
   * [closeSessionAsync] needs in order to complete. It is final, so a subclass cannot swap the
   * graceful close for an abrupt one: put teardown in [closeSessionAsync].
   */
  final override fun close() {
    super.close()
  }

  /**
   * Sends [history], which [sendHistory] has already stripped of audio parts and never passes
   * empty; complete the turn only if the last content is from the user.
   */
  protected abstract fun sendHistoryAsync(history: List<Content>): CompletableFuture<Void?>

  /**
   * Sends [content], which [sendContent] has already checked against [ContentInput]'s rules:
   * function responses answer the model's calls, and other content completes the turn unless
   * [partial] is true.
   */
  protected abstract fun sendContentAsync(
    content: Content,
    partial: Boolean,
  ): CompletableFuture<Void?>

  /** Sends one chunk of media, an activity signal, or the end of the audio stream. */
  protected abstract fun sendRealtimeAsync(input: RealtimeInput): CompletableFuture<Void?>

  /**
   * Returns a publisher of the current model turn's remaining responses that completes at the end
   * of the turn; called once per collection of [receive], never concurrently. Honor `request(n)`,
   * continue after the last response delivered rather than replaying, and hold responses that
   * arrive while no publisher is subscribed. Complete a publisher without responses only once the
   * connection has ended, because that is how the caller learns of it, and fail it instead if the
   * connection dropped.
   */
  protected abstract fun receiveJava(): Publisher<LlmResponse>

  /**
   * Closes the connection gracefully, completing any open [receiveJava] publisher; called at most
   * once, possibly while a send is in flight or a turn is being collected. The base never cancels
   * the returned future and [close] waits for it without a timeout, so complete it within a bounded
   * time even if the peer never answers.
   */
  protected abstract fun closeSessionAsync(): CompletableFuture<Void?>
}

/**
 * Awaits the future [start] returns. A [CancellationException] that [start] throws or the future
 * completes with, while the caller is still active, becomes an [IllegalStateException] naming
 * [hookName], so the caller cannot mistake it for its own cancellation.
 */
internal suspend fun <T> awaitHook(hookName: String, start: () -> CompletionStage<T>): T =
  try {
    start().await()
  } catch (e: CancellationException) {
    if (!currentCoroutineContext().isActive) throw e
    throw IllegalStateException("The call to $hookName was cancelled; fail its future instead", e)
  }

private fun Content.withoutAudioParts(): Content = copy(parts = parts.filterNot { it.isAudio() })

/** Matches Python's `is_audio_part`: an `audio/` mime type on either binary field. */
private fun Part.isAudio(): Boolean =
  inlineData?.mimeType?.startsWith("audio/") == true ||
    fileData?.mimeType?.startsWith("audio/") == true
