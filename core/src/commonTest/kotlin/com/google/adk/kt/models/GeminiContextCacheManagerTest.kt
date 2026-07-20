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

package com.google.adk.kt.models

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ToolConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class GeminiContextCacheManagerTest {

  /** A [GeminiContextCacheManager.CacheClient] that records calls and returns a fixed cache. */
  private class FakeCacheClient(private val createdName: String = "cache/new") :
    GeminiContextCacheManager.CacheClient {
    var createCount = 0
    var deleteCount = 0
    var lastDeletedName: String? = null
    var lastCreateRequest: GeminiContextCacheManager.CacheCreateRequest? = null

    override suspend fun create(request: GeminiContextCacheManager.CacheCreateRequest): String {
      createCount++
      lastCreateRequest = request
      return createdName
    }

    override suspend fun delete(name: String) {
      deleteCount++
      lastDeletedName = name
    }
  }

  private val cacheConfig = ContextCacheConfig()

  private fun textContent(role: String, text: String) =
    Content(role = role, parts = listOf(Part(text = text)))

  // A request whose cacheable prefix is the leading model content (count = 1), leaving the trailing
  // user content to send.
  private fun baseRequest(cacheMetadata: CacheMetadata? = null, tokenCount: Int? = null) =
    LlmRequest(
      contents = listOf(textContent(Role.MODEL, "cached"), textContent(Role.USER, "latest")),
      config = GenerateContentConfig(systemInstruction = textContent(Role.MODEL, "be helpful")),
      cacheConfig = cacheConfig,
      cacheMetadata = cacheMetadata,
      cacheableContentsTokenCount = tokenCount,
    )

  private suspend fun fingerprintFor(
    manager: GeminiContextCacheManager,
    request: LlmRequest,
  ): String =
    manager.handleContextCaching(request.copy(cacheMetadata = null)).cacheMetadata!!.fingerprint

  // A request whose cacheable prefix (a large system instruction, no cached contents) estimates to
  // ~3000 tokens: above Gemini 2.5's 2048 floor but below Gemini 3's 4096 floor.
  private fun floorRequest() =
    LlmRequest(
      contents = listOf(textContent(Role.USER, "hi")),
      config =
        GenerateContentConfig(systemInstruction = textContent(Role.MODEL, "x".repeat(12_000))),
      cacheConfig = cacheConfig,
      cacheableContentsTokenCount = 3000,
    )

  @Test
  fun handleContextCaching_noExistingMetadata_returnsFingerprintOnlyAndDoesNotCreate() = runTest {
    val fake = FakeCacheClient()
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    val request = baseRequest()

    val result = manager.handleContextCaching(request)

    assertNull(result.cacheMetadata?.cacheName)
    assertEquals(1, result.cacheMetadata?.contentsCount)
    // Request is left unchanged (no cache applied).
    assertEquals(request.contents, result.request.contents)
    assertNull(result.request.config.cachedContent)
    assertEquals(0, fake.createCount)
  }

  @Test
  fun handleContextCaching_validActiveCache_appliesCacheToRequest() = runTest {
    val fake = FakeCacheClient()
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    val request = baseRequest()
    val activeMetadata =
      CacheMetadata(
        fingerprint = fingerprintFor(manager, request),
        contentsCount = 1,
        cacheName = "cache/existing",
        expireTime = Clock.System.now().toEpochMilliseconds() + 100_000,
        invocationsUsed = 1,
      )

    val result = manager.handleContextCaching(request.copy(cacheMetadata = activeMetadata))

    assertEquals("cache/existing", result.request.config.cachedContent)
    assertNull(result.request.config.systemInstruction)
    assertEquals(1, result.request.contents.size)
    assertEquals("latest", result.request.contents[0].parts[0].text)
    assertEquals(activeMetadata, result.cacheMetadata)
    assertEquals(0, fake.createCount)
    assertEquals(0, fake.deleteCount)
  }

  @Test
  fun handleContextCaching_expiredCacheSameFingerprintEnoughTokens_recreatesCache() = runTest {
    val fake = FakeCacheClient(createdName = "cache/recreated")
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    // Large enough that the estimated cacheable prefix still clears Gemini's 4096-token floor.
    val request = baseRequest(tokenCount = 8000)
    val expiredMetadata =
      CacheMetadata(
        fingerprint = fingerprintFor(manager, request),
        contentsCount = 1,
        cacheName = "cache/old",
        expireTime = Clock.System.now().toEpochMilliseconds() - 1_000,
        invocationsUsed = 1,
      )

    val result =
      manager.handleContextCaching(
        request.copy(cacheMetadata = expiredMetadata, cacheableContentsTokenCount = 8000)
      )

    assertEquals(1, fake.deleteCount)
    assertEquals("cache/old", fake.lastDeletedName)
    assertEquals(1, fake.createCount)
    // The non-empty cached prefix (the leading model content) is passed through to create.
    assertEquals(1, fake.lastCreateRequest?.contents?.size)
    assertEquals("cache/recreated", result.request.config.cachedContent)
    assertEquals("cache/recreated", result.cacheMetadata?.cacheName)
    assertEquals(1, result.cacheMetadata?.invocationsUsed)
  }

  @Test
  fun handleContextCaching_expiredCacheConversationGrew_recreatesWithGrownPrefix() = runTest {
    val fake = FakeCacheClient(createdName = "cache/grown")
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    // The count-1 prefix ([model "cached"]) is unchanged, so the old count-1 fingerprint still
    // matches; but the conversation grew (findCount is now 2), so recreation must cache the larger
    // prefix (matches Python's max(previousCount, currentCacheableCount)).
    val shortRequest = baseRequest()
    val fingerprintAtCountOne = fingerprintFor(manager, shortRequest)
    val grownRequest =
      shortRequest.copy(
        contents =
          listOf(
            textContent(Role.MODEL, "cached"),
            textContent(Role.MODEL, "more"),
            textContent(Role.USER, "latest"),
          )
      )
    val expiredMetadata =
      CacheMetadata(
        fingerprint = fingerprintAtCountOne,
        contentsCount = 1,
        cacheName = "cache/old",
        expireTime = Clock.System.now().toEpochMilliseconds() - 1_000,
        invocationsUsed = 1,
      )

    val result =
      manager.handleContextCaching(
        grownRequest.copy(cacheMetadata = expiredMetadata, cacheableContentsTokenCount = 8000)
      )

    assertEquals(1, fake.createCount)
    // The recreated cache grew from 1 to 2 contents.
    assertEquals(2, fake.lastCreateRequest?.contents?.size)
    assertEquals(2, result.cacheMetadata?.contentsCount)
  }

  @Test
  fun handleContextCaching_expiredCacheTooFewTokens_returnsFingerprintOnly() = runTest {
    val fake = FakeCacheClient()
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    val request = baseRequest()
    val expiredMetadata =
      CacheMetadata(
        fingerprint = fingerprintFor(manager, request),
        contentsCount = 1,
        cacheName = "cache/old",
        expireTime = Clock.System.now().toEpochMilliseconds() - 1_000,
        invocationsUsed = 1,
      )

    // No token count available, so the cache cannot be recreated.
    val result = manager.handleContextCaching(request.copy(cacheMetadata = expiredMetadata))

    assertEquals(1, fake.deleteCount)
    assertEquals(0, fake.createCount)
    assertNull(result.cacheMetadata?.cacheName)
    assertEquals(1, result.cacheMetadata?.contentsCount)
  }

  @Test
  fun handleContextCaching_differentModel_doesNotReuseCache() = runTest {
    val request = baseRequest()
    // Fingerprint captured under one model must not validate an active cache under another, because
    // explicit caches are model-specific (the model is part of the fingerprint).
    val flashFingerprint =
      fingerprintFor(GeminiContextCacheManager("gemini-2.0-flash", FakeCacheClient()), request)
    val activeMetadata =
      CacheMetadata(
        fingerprint = flashFingerprint,
        contentsCount = 1,
        cacheName = "cache/flash",
        expireTime = Clock.System.now().toEpochMilliseconds() + 100_000,
        invocationsUsed = 1,
      )

    val proFake = FakeCacheClient()
    val proManager = GeminiContextCacheManager("gemini-1.5-pro", proFake)
    val result = proManager.handleContextCaching(request.copy(cacheMetadata = activeMetadata))

    assertNull(result.request.config.cachedContent)
    assertEquals(1, proFake.deleteCount)
    assertEquals("cache/flash", proFake.lastDeletedName)
    assertNull(result.cacheMetadata?.cacheName)
  }

  @Test
  fun handleContextCaching_validCache_stripsToolConfig() = runTest {
    val fake = FakeCacheClient()
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    val request = baseRequest().let { it.copy(config = it.config.copy(toolConfig = ToolConfig())) }
    val activeMetadata =
      CacheMetadata(
        fingerprint = fingerprintFor(manager, request),
        contentsCount = 1,
        cacheName = "cache/existing",
        expireTime = Clock.System.now().toEpochMilliseconds() + 100_000,
        invocationsUsed = 1,
      )

    val result = manager.handleContextCaching(request.copy(cacheMetadata = activeMetadata))

    assertEquals("cache/existing", result.request.config.cachedContent)
    assertNull(result.request.config.toolConfig)
  }

  @Test
  fun handleContextCaching_prefixBelowMinimumEvenWhenFullClears_doesNotCreate() = runTest {
    val fake = FakeCacheClient()
    val manager = GeminiContextCacheManager("gemini-2.5-flash", fake)
    // Tiny cached prefix but a huge trailing (uncached) user turn: the full previous prompt clears
    // Gemini 2.5's 2048-token floor, but the estimated cacheable prefix does not, so no cache is
    // created.
    val request =
      LlmRequest(
        contents =
          listOf(textContent(Role.MODEL, "cached"), textContent(Role.USER, "y".repeat(100_000))),
        config = GenerateContentConfig(systemInstruction = textContent(Role.MODEL, "be helpful")),
        cacheConfig = cacheConfig,
        cacheableContentsTokenCount = 5000,
      )
    val expiredMetadata =
      CacheMetadata(
        fingerprint = fingerprintFor(manager, request),
        contentsCount = 1,
        cacheName = "cache/old",
        expireTime = Clock.System.now().toEpochMilliseconds() - 1_000,
        invocationsUsed = 1,
      )

    val result =
      manager.handleContextCaching(
        request.copy(cacheMetadata = expiredMetadata, cacheableContentsTokenCount = 5000)
      )

    assertEquals(1, fake.deleteCount)
    assertEquals(0, fake.createCount)
    assertNull(result.cacheMetadata?.cacheName)
  }

  @Test
  fun handleContextCaching_contentsCount_cachesBeforeLastUserBatch() = runTest {
    val manager = GeminiContextCacheManager("gemini-2.0-flash", FakeCacheClient())

    suspend fun countFor(vararg roles: String): Int {
      val contents = roles.mapIndexed { i, role -> textContent(role, "m$i") }
      val request = LlmRequest(contents = contents, cacheConfig = cacheConfig)
      return manager.handleContextCaching(request).cacheMetadata!!.contentsCount
    }

    // Empty contents: nothing to cache.
    assertEquals(0, countFor())
    // A single user turn has nothing before it to cache.
    assertEquals(0, countFor(Role.USER))
    // Cache everything before the trailing contiguous user batch.
    assertEquals(2, countFor(Role.USER, Role.MODEL, Role.USER))
    assertEquals(4, countFor(Role.USER, Role.MODEL, Role.USER, Role.MODEL, Role.USER))
    // A model turn last means no trailing user batch: the degenerate "cache everything" count.
    assertEquals(6, countFor(Role.USER, Role.MODEL, Role.USER, Role.MODEL, Role.USER, Role.MODEL))
  }

  @Test
  fun handleContextCaching_equivalentArgMappingOrder_produceSameFingerprint() = runTest {
    val manager = GeminiContextCacheManager("gemini-2.0-flash", FakeCacheClient())

    fun requestWithArgs(args: Map<String, Any?>) =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = Role.MODEL,
              parts = listOf(Part(functionCall = FunctionCall(name = "lookup", args = args))),
            ),
            textContent(Role.USER, "latest"),
          ),
        config = GenerateContentConfig(systemInstruction = textContent(Role.MODEL, "be helpful")),
        cacheConfig = cacheConfig,
      )

    // Same args in different key insertion order: the canonical fingerprint must ignore order.
    val first = fingerprintFor(manager, requestWithArgs(mapOf("first" to 1, "second" to 2)))
    val second = fingerprintFor(manager, requestWithArgs(mapOf("second" to 2, "first" to 1)))

    assertEquals(first, second)
  }

  @Test
  fun handleContextCaching_gemini25_createsCacheAbove2048Floor() = runTest {
    val fake = FakeCacheClient(createdName = "cache/gemini25")
    val manager = GeminiContextCacheManager("gemini-2.5-flash", fake)
    val request = floorRequest()
    val metadata = CacheMetadata(fingerprint = fingerprintFor(manager, request), contentsCount = 0)

    val result = manager.handleContextCaching(request.copy(cacheMetadata = metadata))

    // ~3000 estimated prefix tokens clears Gemini 2.5's 2048-token floor.
    assertEquals(1, fake.createCount)
    assertEquals("cache/gemini25", result.cacheMetadata?.cacheName)
  }

  @Test
  fun handleContextCaching_gemini3_skipsCacheBelow4096Floor() = runTest {
    val fake = FakeCacheClient()
    val manager = GeminiContextCacheManager("gemini-3.1-pro-preview", fake)
    val request = floorRequest()
    val metadata = CacheMetadata(fingerprint = fingerprintFor(manager, request), contentsCount = 0)

    val result = manager.handleContextCaching(request.copy(cacheMetadata = metadata))

    // ~3000 estimated prefix tokens is below Gemini 3's 4096-token floor.
    assertEquals(0, fake.createCount)
    assertNull(result.cacheMetadata?.cacheName)
  }

  @Test
  fun handleContextCaching_opaqueModel_leavesTokenFloorToServer() = runTest {
    val fake = FakeCacheClient(createdName = "cache/tuned")
    val manager =
      GeminiContextCacheManager("projects/p/locations/us-central1/endpoints/tuned-model", fake)
    val request = floorRequest()
    val metadata = CacheMetadata(fingerprint = fingerprintFor(manager, request), contentsCount = 0)

    val result = manager.handleContextCaching(request.copy(cacheMetadata = metadata))

    // Opaque model IDs have no client-side floor, so creation is attempted (the server decides).
    assertEquals(1, fake.createCount)
    assertEquals("cache/tuned", result.cacheMetadata?.cacheName)
  }

  @Test
  fun handleContextCaching_createWithEmptyPrefix_passesNullContents() = runTest {
    val fake = FakeCacheClient(createdName = "cache/sysonly")
    val manager = GeminiContextCacheManager("gemini-2.5-flash", fake)
    // All-user contents: the cached prefix is 0 contents (system instruction + tools only).
    val request = floorRequest()
    val metadata = CacheMetadata(fingerprint = fingerprintFor(manager, request), contentsCount = 0)

    val result = manager.handleContextCaching(request.copy(cacheMetadata = metadata))

    assertEquals(1, fake.createCount)
    // An empty prefix is passed as null (an absent contents field), not an empty list.
    assertNull(fake.lastCreateRequest?.contents)
    assertEquals("cache/sysonly", result.cacheMetadata?.cacheName)
  }
}
