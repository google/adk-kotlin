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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.summarizer.TokenThresholdEventCompactor

/**
 * Runs token-threshold event compaction before the conversation history is assembled for a model
 * call.
 *
 * When the invocation's [InvocationContext.eventsCompactionConfig] has token-threshold compaction
 * configured and the most recently observed prompt token count has reached the threshold, this
 * appends a compaction summary event to the session. Because it runs before [ContentsProcessor],
 * the freshly appended summary is reflected in the contents built for the request. The [LlmRequest]
 * itself is returned unchanged.
 */
internal class CompactionRequestProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val config = context.eventsCompactionConfig ?: return request
    if (!config.hasTokenThresholdConfig()) return request
    val sessionService = context.sessionService ?: return request
    TokenThresholdEventCompactor(config, context.agent.name, context.branch)
      .compact(context.session, sessionService)
    return request
  }
}
