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
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.models.LlmRequest

/**
 * A request processor that enables context caching for the LLM request.
 *
 * When the invocation has a [InvocationContext.contextCacheConfig], this copies the config onto the
 * request and recovers the most recent cache metadata and prompt token count from the current
 * agent's session events, so the model-side cache manager (e.g. the Gemini cache manager) can
 * validate and reuse an existing cache. When no config is present, the request is returned
 * unchanged and caching stays disabled. The actual cache lifecycle is handled by the model.
 */
internal class ContextCacheRequestProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val cacheConfig = context.contextCacheConfig ?: return request

    val (cacheMetadata, previousTokenCount) =
      findCacheInfoFromEvents(context.session.events, context.agent.name, context.invocationId)

    return request.copy(
      cacheConfig = cacheConfig,
      cacheMetadata = cacheMetadata,
      cacheableContentsTokenCount = previousTokenCount,
    )
  }

  /**
   * Scans [events] (most recent first) for the latest cache metadata and prompt token count
   * authored by [agentName].
   *
   * The returned cache metadata has its [CacheMetadata.invocationsUsed] incremented by one when it
   * comes from a different (earlier) invocation than [currentInvocationId] and refers to an active
   * cache. This mirrors the Python ADK behavior of counting one additional reuse per invocation.
   *
   * @return A pair of the recovered cache metadata (or `null`) and the most recent prompt token
   *   count (or `null`).
   */
  private fun findCacheInfoFromEvents(
    events: List<Event>,
    agentName: String,
    currentInvocationId: String,
  ): Pair<CacheMetadata?, Int?> {
    var cacheMetadata: CacheMetadata? = null
    var previousTokenCount: Int? = null

    for (event in events.asReversed()) {
      if (event.author != agentName) continue

      if (cacheMetadata == null) {
        val eventCacheMetadata = event.cacheMetadata
        if (eventCacheMetadata != null) {
          cacheMetadata =
            if (
              event.invocationId != null &&
                event.invocationId != currentInvocationId &&
                eventCacheMetadata.cacheName != null
            ) {
              // Different invocation with an active cache: count one more reuse.
              eventCacheMetadata.copy(invocationsUsed = eventCacheMetadata.invocationsUsed!! + 1)
            } else {
              eventCacheMetadata
            }
        }
      }

      if (previousTokenCount == null) {
        previousTokenCount = event.usageMetadata?.promptTokenCount
      }

      if (cacheMetadata != null && previousTokenCount != null) break
    }

    return cacheMetadata to previousTokenCount
  }
}
