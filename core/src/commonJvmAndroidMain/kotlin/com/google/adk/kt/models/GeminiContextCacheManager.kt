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

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.toGenaiSdk
import com.google.genai.types.CachedContent
import com.google.genai.types.CreateCachedContentConfig
import java.security.MessageDigest
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock

/**
 * Manages the context-cache lifecycle for Gemini models.
 *
 * This handles cache creation, validation, cleanup, and metadata population for Gemini context
 * caching. It uses content hashing (a fingerprint) to determine cache compatibility and applies an
 * existing cache to the request by referencing it via [GenerateContentConfig.cachedContent] and
 * dropping the cached prefix from the request.
 *
 * Cache operations are delegated to a [CacheClient] so they can be faked in tests. The Gemini model
 * wires in a [RealCacheClient] backed by the GenAI SDK's `client.caches`.
 *
 * @param modelName The model resource name used when creating caches.
 * @param cacheClient The client used to create and delete Gemini caches.
 */
internal class GeminiContextCacheManager(
  private val modelName: String,
  private val cacheClient: CacheClient,
) {

  /**
   * The outcome of [handleContextCaching]: the (possibly rewritten) [request] to send to the model
   * and the [cacheMetadata] to attach to the response, or `null` metadata if caching produced no
   * usable state.
   */
  data class CacheResult(val request: LlmRequest, val cacheMetadata: CacheMetadata?)

  /**
   * Validates an existing cache or creates a new one if needed, then applies the cache to the
   * request by setting `cachedContent` and dropping the cached prefix.
   *
   * @param request The request that may carry cache config and metadata.
   * @return The (possibly rewritten) request plus the cache metadata to include in the response.
   */
  fun handleContextCaching(request: LlmRequest): CacheResult {
    val existing = request.cacheMetadata
    if (existing != null) {
      if (isCacheValid(request)) {
        logger.debug { "Cache is valid, reusing cache: ${existing.cacheName}" }
        val applied = applyCacheToRequest(request, existing.cacheName!!, existing.contentsCount)
        return CacheResult(applied, existing)
      }

      // Invalid cache: clean up any active cache, then decide whether to recreate.
      if (existing.cacheName != null) {
        logger.debug { "Cache is invalid, cleaning up: ${existing.cacheName}" }
        cleanupCache(existing.cacheName)
      }

      val cacheContentsCount = existing.contentsCount
      val currentFingerprint = generateFingerprint(request, cacheContentsCount)

      if (currentFingerprint == existing.fingerprint) {
        // Same content but the cache expired/aged out: recreate it.
        val created = createNewCacheWithContents(request, cacheContentsCount)
        if (created != null) {
          val applied = applyCacheToRequest(request, created.cacheName!!, cacheContentsCount)
          return CacheResult(applied, created)
        }
        // Creation failed (e.g. below the token minimum): preserve the prefix fingerprint so the
        // fingerprint chain stays stable for subsequent calls.
        return CacheResult(
          request,
          CacheMetadata(fingerprint = currentFingerprint, contentsCount = cacheContentsCount),
        )
      }

      // Fingerprints differ: recompute over the current cacheable prefix and return
      // fingerprint-only metadata.
      val newCount = findCountOfContentsToCache(request.contents)
      val fingerprint = generateFingerprint(request, newCount)
      return CacheResult(
        request,
        CacheMetadata(fingerprint = fingerprint, contentsCount = newCount),
      )
    }

    // No existing metadata: return fingerprint-only metadata. A cache is never created without a
    // prior fingerprint to match against.
    val cacheContentsCount = findCountOfContentsToCache(request.contents)
    val fingerprint = generateFingerprint(request, cacheContentsCount)
    return CacheResult(
      request,
      CacheMetadata(fingerprint = fingerprint, contentsCount = cacheContentsCount),
    )
  }

  /** Returns [response] with [cacheMetadata] attached. */
  fun populateCacheMetadataInResponse(
    response: LlmResponse,
    cacheMetadata: CacheMetadata,
  ): LlmResponse = response.copy(cacheMetadata = cacheMetadata)

  /**
   * Finds the number of leading contents to cache: everything before the last contiguous batch of
   * user contents. This always leaves at least the latest user turn to send to the API.
   */
  private fun findCountOfContentsToCache(contents: List<Content>): Int {
    if (contents.isEmpty()) return 0
    var lastUserBatchStart = contents.size
    for (i in contents.indices.reversed()) {
      if (contents[i].role == Role.USER) {
        lastUserBatchStart = i
      } else {
        break
      }
    }
    return lastUserBatchStart
  }

  /** Checks whether the cache referenced by the request's metadata is still usable. */
  private fun isCacheValid(request: LlmRequest): Boolean {
    val metadata = request.cacheMetadata ?: return false
    // Fingerprint-only metadata is not an active cache.
    if (metadata.cacheName == null) return false
    if (Clock.System.now().toEpochMilliseconds() >= metadata.expireTime!!) {
      logger.info { "Cache expired: ${metadata.cacheName}" }
      return false
    }
    val maxInvocations = request.cacheConfig?.maxInvocations ?: return false
    if (metadata.invocationsUsed!! > maxInvocations) {
      logger.info { "Cache exceeded max invocations: ${metadata.cacheName}" }
      return false
    }
    val currentFingerprint = generateFingerprint(request, metadata.contentsCount)
    if (currentFingerprint != metadata.fingerprint) {
      logger.debug { "Cache content fingerprint mismatch" }
      return false
    }
    return true
  }

  /**
   * Generates a 16-character fingerprint over the cacheable state: system instruction, tools, and
   * the first [cacheContentsCount] contents. Uses the GenAI SDK JSON form for stable hashing of
   * binary parts.
   */
  private fun generateFingerprint(request: LlmRequest, cacheContentsCount: Int): String {
    val builder = StringBuilder()
    request.config.systemInstruction?.let {
      builder.append("system_instruction:").append(it.toGenaiSdk().toJson())
    }
    request.config.tools?.let { tools ->
      builder.append("tools:")
      for (tool in tools) {
        builder.append(tool.toGenaiSdk().toJson())
      }
    }
    if (cacheContentsCount > 0 && request.contents.isNotEmpty()) {
      builder.append("contents:")
      val count = minOf(cacheContentsCount, request.contents.size)
      for (i in 0 until count) {
        builder.append(request.contents[i].toGenaiSdk().toJson())
      }
    }
    return sha256Hex(builder.toString()).take(FINGERPRINT_LENGTH)
  }

  /** Creates a new cache when the previous request was large enough, otherwise returns `null`. */
  private fun createNewCacheWithContents(
    request: LlmRequest,
    cacheContentsCount: Int,
  ): CacheMetadata? {
    val tokenCount = request.cacheableContentsTokenCount
    if (tokenCount == null) {
      logger.info { "No previous token count available; skipping cache creation." }
      return null
    }
    val minTokens = request.cacheConfig?.minTokens ?: return null
    if (tokenCount < minTokens) {
      logger.info { "Previous request too small for caching ($tokenCount < $minTokens tokens)." }
      return null
    }
    if (tokenCount < GEMINI_MIN_CACHE_TOKENS) {
      logger.info {
        "Request below Gemini minimum cache size ($tokenCount < $GEMINI_MIN_CACHE_TOKENS tokens)."
      }
      return null
    }
    return try {
      createGeminiCache(request, cacheContentsCount)
    } catch (e: Exception) {
      logger.warn { "Failed to create cache: ${e.message}" }
      null
    }
  }

  /** Creates the cache via the GenAI SDK and returns its metadata. */
  private fun createGeminiCache(request: LlmRequest, cacheContentsCount: Int): CacheMetadata {
    val cacheConfig = requireNotNull(request.cacheConfig) { "cacheConfig must be set." }
    val cacheContents = request.contents.take(cacheContentsCount).map { it.toGenaiSdk() }
    val displayName = "adk-cache-${Clock.System.now().epochSeconds}-${cacheContentsCount}contents"

    val configBuilder =
      CreateCachedContentConfig.builder()
        .contents(cacheContents)
        .ttl(java.time.Duration.ofMillis(cacheConfig.ttl.inWholeMilliseconds))
        .displayName(displayName)
    request.config.systemInstruction?.let { configBuilder.systemInstruction(it.toGenaiSdk()) }
    request.config.tools?.let { tools -> configBuilder.tools(tools.map { it.toGenaiSdk() }) }

    val cachedContent: CachedContent = cacheClient.create(modelName, configBuilder.build())
    val createdAt = Clock.System.now().toEpochMilliseconds()
    val cacheName =
      cachedContent.name().getOrNull()
        ?: throw IllegalStateException("Created cache has no resource name.")
    logger.info { "Cache created successfully: $cacheName" }

    return CacheMetadata(
      fingerprint = generateFingerprint(request, cacheContentsCount),
      contentsCount = cacheContentsCount,
      cacheName = cacheName,
      expireTime = createdAt + cacheConfig.ttl.inWholeMilliseconds,
      invocationsUsed = 1,
      createdAt = createdAt,
    )
  }

  /** Deletes a cache, logging and swallowing any failure. */
  private fun cleanupCache(cacheName: String) {
    try {
      cacheClient.delete(cacheName)
      logger.info { "Cache cleaned up: $cacheName" }
    } catch (e: Exception) {
      logger.warn { "Failed to cleanup cache $cacheName: ${e.message}" }
    }
  }

  /**
   * Rewrites the request to use the cache: references it via `cachedContent`, drops the cached
   * system instruction and tools, and removes the cached content prefix.
   */
  private fun applyCacheToRequest(
    request: LlmRequest,
    cacheName: String,
    cacheContentsCount: Int,
  ): LlmRequest =
    request.copy(
      config =
        request.config.copy(systemInstruction = null, tools = null, cachedContent = cacheName),
      contents = request.contents.drop(cacheContentsCount),
    )

  private fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
    return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
  }

  /** Abstraction over the GenAI SDK cache operations to allow faking in tests. */
  interface CacheClient {
    fun create(model: String, config: CreateCachedContentConfig): CachedContent

    fun delete(name: String)
  }

  /** [CacheClient] backed by the GenAI SDK's `client.caches`. */
  class RealCacheClient(private val caches: com.google.genai.Caches) : CacheClient {
    override fun create(model: String, config: CreateCachedContentConfig): CachedContent =
      caches.create(model, config)

    override fun delete(name: String) {
      val unused = caches.delete(name, null)
    }
  }

  private companion object {
    private val logger = LoggerFactory.getLogger(GeminiContextCacheManager::class)

    // Gemini requires a minimum number of tokens before content can be cached.
    private const val GEMINI_MIN_CACHE_TOKENS = 4096

    private const val FINGERPRINT_LENGTH = 16
  }
}
