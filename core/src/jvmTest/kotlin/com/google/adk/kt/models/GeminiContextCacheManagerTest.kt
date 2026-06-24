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
package com.google.adk.kt.models

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.genai.types.CachedContent
import com.google.genai.types.CreateCachedContentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class GeminiContextCacheManagerTest {

  /** A [GeminiContextCacheManager.CacheClient] that records calls and returns a fixed cache. */
  private class FakeCacheClient(private val createdName: String = "cache/new") :
    GeminiContextCacheManager.CacheClient {
    var createCount = 0
    var deleteCount = 0
    var lastDeletedName: String? = null

    override fun create(model: String, config: CreateCachedContentConfig): CachedContent {
      createCount++
      return CachedContent.builder().name(createdName).build()
    }

    override fun delete(name: String) {
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

  private fun fingerprintFor(manager: GeminiContextCacheManager, request: LlmRequest): String =
    manager.handleContextCaching(request.copy(cacheMetadata = null)).cacheMetadata!!.fingerprint

  @Test
  fun handleContextCaching_noExistingMetadata_returnsFingerprintOnlyAndDoesNotCreate() {
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
  fun handleContextCaching_validActiveCache_appliesCacheToRequest() {
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
  fun handleContextCaching_expiredCacheSameFingerprintEnoughTokens_recreatesCache() {
    val fake = FakeCacheClient(createdName = "cache/recreated")
    val manager = GeminiContextCacheManager("gemini-2.0-flash", fake)
    val request = baseRequest(tokenCount = 5000)
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
    assertEquals("cache/old", fake.lastDeletedName)
    assertEquals(1, fake.createCount)
    assertEquals("cache/recreated", result.request.config.cachedContent)
    assertEquals("cache/recreated", result.cacheMetadata?.cacheName)
    assertEquals(1, result.cacheMetadata?.invocationsUsed)
  }

  @Test
  fun handleContextCaching_expiredCacheTooFewTokens_returnsFingerprintOnly() {
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
  fun populateCacheMetadataInResponse_attachesMetadata() {
    val manager = GeminiContextCacheManager("gemini-2.0-flash", FakeCacheClient())
    val response = LlmResponse(content = textContent(Role.MODEL, "ok"))
    val metadata = CacheMetadata(fingerprint = "fp", contentsCount = 0)

    val result = manager.populateCacheMetadataInResponse(response, metadata)

    assertEquals(metadata, result.cacheMetadata)
  }
}
