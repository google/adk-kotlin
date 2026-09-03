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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a [BaseAgent]. A Java subclass overrides [runAsyncImplJava],
 * which returns a Reactive Streams [Publisher] of the turn's events; this base adapts it to the
 * `Flow`-returning [runAsyncImpl] contract that Java cannot express. The publisher is subscribed to
 * lazily and honours backpressure.
 */
@AdkJavaInteropApi
abstract class BasePublisherAgent
@JvmOverloads
constructor(
  name: String,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  disallowTransferToParent: Boolean = false,
  disallowTransferToPeers: Boolean = false,
) :
  BaseAgent(
    name,
    description,
    subAgents,
    beforeAgentCallbacks,
    afterAgentCallbacks,
    disallowTransferToParent,
    disallowTransferToPeers,
  ) {

  /** Produces this agent's events. Called once per invocation, after the before-agent callbacks. */
  @JvmSuppressWildcards
  protected abstract fun runAsyncImplJava(context: InvocationContext): Publisher<Event>

  final override fun runAsyncImpl(context: InvocationContext): Flow<Event> =
    runAsyncImplJava(context).asFlow()
}
