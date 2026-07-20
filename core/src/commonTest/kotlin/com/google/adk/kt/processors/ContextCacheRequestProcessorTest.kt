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

@file:OptIn(com.google.adk.kt.annotations.ExperimentalContextCachingFeature::class)

package com.google.adk.kt.processors

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ContextCacheRequestProcessorTest {

  private val agent = DummyAgent(name = "agent")

  private fun contextWith(
    config: ContextCacheConfig?,
    events: List<Event> = emptyList(),
    invocationId: String = "inv-current",
  ): InvocationContext {
    val session = testSession()
    session.events.addAll(events)
    return InvocationContext(
      session = session,
      runConfig = null,
      agent = agent,
      invocationId = invocationId,
      contextCacheConfig = config,
    )
  }

  private fun activeCacheMetadata(invocationsUsed: Int) =
    CacheMetadata(
      fingerprint = "fp",
      contentsCount = 3,
      cacheName = "cache/1",
      expireTime = 1_000L,
      invocationsUsed = invocationsUsed,
    )

  @Test
  fun process_noCacheConfig_returnsRequestUnchanged() = runBlocking {
    val context = contextWith(config = null)
    val request = LlmRequest()

    val result = ContextCacheRequestProcessor().process(context, request)

    assertEquals(request, result)
    assertNull(result.cacheConfig)
  }

  @Test
  fun process_cacheConfigButNoEvents_setsConfigOnly() = runBlocking {
    val config = ContextCacheConfig()
    val context = contextWith(config = config)

    val result = ContextCacheRequestProcessor().process(context, LlmRequest())

    assertEquals(config, result.cacheConfig)
    assertNull(result.cacheMetadata)
    assertNull(result.cacheableContentsTokenCount)
  }

  @Test
  fun process_sameInvocationActiveCache_doesNotIncrementInvocationsUsed() = runBlocking {
    val event =
      Event(
        author = "agent",
        invocationId = "inv-current",
        cacheMetadata = activeCacheMetadata(invocationsUsed = 2),
        usageMetadata = UsageMetadata(promptTokenCount = 5000),
      )
    val context =
      contextWith(
        config = ContextCacheConfig(),
        events = listOf(event),
        invocationId = "inv-current",
      )

    val result = ContextCacheRequestProcessor().process(context, LlmRequest())

    assertEquals(2, result.cacheMetadata?.invocationsUsed)
    assertEquals(5000, result.cacheableContentsTokenCount)
  }

  @Test
  fun process_priorInvocationActiveCache_incrementsInvocationsUsed() = runBlocking {
    val event =
      Event(
        author = "agent",
        invocationId = "inv-previous",
        cacheMetadata = activeCacheMetadata(invocationsUsed = 2),
      )
    val context =
      contextWith(
        config = ContextCacheConfig(),
        events = listOf(event),
        invocationId = "inv-current",
      )

    val result = ContextCacheRequestProcessor().process(context, LlmRequest())

    assertEquals(3, result.cacheMetadata?.invocationsUsed)
  }

  @Test
  fun process_priorInvocationFingerprintOnly_doesNotIncrement() = runBlocking {
    val event =
      Event(
        author = "agent",
        invocationId = "inv-previous",
        cacheMetadata = CacheMetadata(fingerprint = "fp", contentsCount = 3),
      )
    val context =
      contextWith(
        config = ContextCacheConfig(),
        events = listOf(event),
        invocationId = "inv-current",
      )

    val result = ContextCacheRequestProcessor().process(context, LlmRequest())

    assertNull(result.cacheMetadata?.invocationsUsed)
    assertEquals(3, result.cacheMetadata?.contentsCount)
  }

  @Test
  fun process_ignoresEventsFromOtherAgents() = runBlocking {
    val otherAgentEvent =
      Event(
        author = "other",
        invocationId = "inv-previous",
        cacheMetadata = CacheMetadata(fingerprint = "fp", contentsCount = 1),
        usageMetadata = UsageMetadata(promptTokenCount = 9999),
      )
    val context = contextWith(config = ContextCacheConfig(), events = listOf(otherAgentEvent))

    val result = ContextCacheRequestProcessor().process(context, LlmRequest())

    assertNull(result.cacheMetadata)
    assertNull(result.cacheableContentsTokenCount)
  }
}
