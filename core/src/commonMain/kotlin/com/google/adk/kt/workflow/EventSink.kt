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

package com.google.adk.kt.workflow

import com.google.adk.kt.events.Event
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope

/** Destination for events emitted during a workflow execution. */
internal fun interface EventSink {
  /**
   * Sends [event]; [nodeFailure] is set when [event] is the error event of a node that failed with
   * it, so a sink that cannot deliver the event can still report the failure.
   */
  suspend fun send(event: Event, nodeFailure: Exception?)

  suspend fun send(event: Event) = send(event, nodeFailure = null)
}

/**
 * Runs [block] with a sink that passes each event to [emit] on the calling coroutine. A non-partial
 * event suspends its sender until [emit] returns, so [emit] can persist it first. If [emit] throws
 * an Exception that is not a cancellation, each queued node failure is attached to it as
 * suppressed.
 */
// Confined to the coroutineScope: block sends to the channel, and emit runs on the caller.
@Suppress("UnsafeCoroutineCrossing")
internal suspend fun <T> withCommittedEventSink(
  emit: suspend (Event) -> Unit,
  block: suspend (EventSink) -> T,
): T = coroutineScope {
  val channel = Channel<QueuedEvent>(Channel.BUFFERED)
  val result = async {
    try {
      block(
        EventSink { event, nodeFailure ->
          val processed = if (event.partial) null else CompletableDeferred<Unit>()
          channel.send(QueuedEvent(event, processed, nodeFailure))
          processed?.await()
        }
      )
    } finally {
      // Explicit cause: no-arg close() fails to link against kotlinx-coroutines < 1.11.
      val unused = channel.close(cause = null)
    }
  }
  // If emit throws, the scope cancels block.
  channel.consumeEach { (event, processed, nodeFailure) ->
    try {
      emit(event)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A cancelled node cannot record its failure, so attach the failure here.
      if (nodeFailure != null) e.addSuppressed(nodeFailure)
      // The queue can also contain other error events, so attach their failures.
      while (true) {
        val queued = channel.tryReceive().getOrNull() ?: break
        queued.nodeFailure?.let { e.addSuppressed(it) }
      }
      throw e
    }
    processed?.complete(Unit)
  }
  result.await()
}

/**
 * An event on its way to [withCommittedEventSink]'s caller; [processed] is null for a partial
 * event, and [nodeFailure] is set when [event] is the error event of a node that failed with it.
 */
private data class QueuedEvent(
  val event: Event,
  val processed: CompletableDeferred<Unit>?,
  val nodeFailure: Exception?,
)

/**
 * Carries the currently running workflow node's [EventSink] in the [CoroutineContext]. A
 * `BaseAgent` node does not pass its node `Context` to `runAsync`, so tools called by an agent node
 * read the sink from here to emit child-node events without the agent treating them as its own
 * output.
 */
internal class ActiveNodeSink(val invocationId: String, val sink: EventSink) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<ActiveNodeSink>
}
