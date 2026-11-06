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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * Adapts this publisher to a [Flow] that requests one item at a time, the next only after the
 * collector has processed the previous one. The flow buffers items the publisher sends beyond that
 * demand. `asFlow().buffer(Channel.RENDEZVOUS)` throws on them instead, and RxJava can swallow that
 * exception so the flow never completes.
 */
internal fun <T : Any> Publisher<T>.asFlowOneAtATime(): Flow<T> = flow {
  val items = Channel<T>(Channel.UNLIMITED)
  val subscribed = CompletableDeferred<Subscription>()
  subscribe(
    object : Subscriber<T> {
      override fun onSubscribe(s: Subscription) {
        // Request now: publishers such as Flowable.create can emit before subscribe returns.
        if (subscribed.complete(s)) s.request(1) else s.cancel()
      }

      override fun onNext(item: T) {
        // The channel is unlimited, so this fails only after the flow or the publisher has ended.
        val unused = items.trySend(item)
      }

      override fun onError(t: Throwable) {
        items.close(t)
      }

      override fun onComplete() {
        // Explicit cause: no-arg close() fails to link against kotlinx-coroutines < 1.11.
        val unused = items.close(cause = null)
      }
    }
  )
  try {
    val subscription = subscribed.await()
    for (item in items) {
      emit(item)
      subscription.request(1)
    }
  } finally {
    // If onSubscribe has not run yet, it will cancel its own subscription when it runs.
    if (!subscribed.completeExceptionally(CancellationException())) {
      subscribed.getCompleted().cancel()
    }
    // Explicit cause: no-arg cancel() fails to link against kotlinx-coroutines < 1.11.
    items.cancel(cause = null)
  }
}
