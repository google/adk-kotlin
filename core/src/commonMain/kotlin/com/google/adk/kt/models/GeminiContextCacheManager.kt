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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.crypto.sha256Hex
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.ToolConfig
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Manages the context-cache lifecycle for Gemini models.
 *
 * This handles cache creation, validation, cleanup, and metadata population for Gemini context
 * caching. It uses content hashing (a fingerprint) to determine cache compatibility and applies an
 * existing cache to the request by referencing it via [GenerateContentConfig.cachedContent] and
 * dropping the cached prefix from the request.
 *
 * The cache backend is delegated to a [CacheClient] interface so the manager stays decoupled from
 * the GenAI SDK and can be faked in tests: the SDK's cache types are final with internal
 * constructors and cannot be faked directly. The Gemini model wires in the SDK-backed
 * `GenaiCacheClient`.
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
  suspend fun handleContextCaching(request: LlmRequest): CacheResult {
    val existing = request.cacheMetadata
    if (existing != null) {
      if (isCacheValid(request)) {
        logger.debug { "Cache is valid, reusing cache: ${existing.cacheName}" }
        val applied = applyCacheToRequest(request, existing.cacheName!!, existing.contentsCount)
        return CacheResult(applied, existing)
      }

      // Invalid cache: clean up any active cache, then decide whether to recreate. Only a real
      // cache has a backend resource to delete; a fingerprint-only record (cacheName == null) has
      // nothing to delete.
      val staleCacheName = existing.cacheName
      if (staleCacheName != null) {
        logger.debug { "Cache is invalid, cleaning up: $staleCacheName" }
        cleanupCache(staleCacheName)
      }

      val cacheContentsCount = existing.contentsCount
      val currentFingerprint = generateFingerprint(request, cacheContentsCount)

      if (currentFingerprint == existing.fingerprint) {
        // The previously cached prefix is unchanged (fingerprints match) but the cache expired.
        // Recreate it, growing the prefix to the current cacheable boundary (newly settled history)
        // so it keeps up as the conversation grows, matching Python's
        // max(previousCount, currentCacheableCount).
        val newCount = maxOf(cacheContentsCount, findCountOfContentsToCache(request.contents))
        val newFingerprint = generateFingerprint(request, newCount)
        val created = createNewCacheWithContents(request, newCount)
        if (created != null) {
          val applied = applyCacheToRequest(request, created.cacheName!!, newCount)
          return CacheResult(applied, created)
        }
        // Creation failed (e.g. below the token minimum): preserve the grown prefix fingerprint so
        // the fingerprint chain stays stable for subsequent calls.
        return CacheResult(
          request,
          CacheMetadata(fingerprint = newFingerprint, contentsCount = newCount),
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

  /**
   * Finds the number of leading contents to cache: everything before the last contiguous batch of
   * user contents. This always leaves at least the latest user turn to send to the API.
   *
   * Callers run `ensureModelResponse` (via `prepareGenerateContentRequest`) before caching, so the
   * last content is always a user turn; the `contents.size` fallback for a non-user or empty tail
   * is therefore unreachable in the real flow.
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
    val cacheIntervals = request.cacheConfig?.cacheIntervals ?: return false
    if (metadata.invocationsUsed!! > cacheIntervals) {
      logger.info {
        "Cache exceeded cache intervals: ${metadata.cacheName} " +
          "(${metadata.invocationsUsed} > $cacheIntervals)"
      }
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
   * Generates a 16-character fingerprint over the cacheable state: model, system instruction,
   * tools, tool config, and the first [cacheContentsCount] contents. Serializes them into a single
   * canonical JSON object with every object's keys recursively sorted, so the fingerprint depends
   * only on content, not on map insertion or property order, and has no field-boundary ambiguity.
   * The model is included because explicit caches are model-specific.
   */
  @OptIn(FrameworkInternalApi::class)
  private fun generateFingerprint(request: LlmRequest, cacheContentsCount: Int): String {
    val fields =
      buildMap<String, JsonElement> {
        this["model"] = JsonPrimitive(modelName)
        request.config.systemInstruction?.let {
          this["system_instruction"] = adkJson.encodeToJsonElement(it)
        }
        request.config.tools?.let { this["tools"] = adkJson.encodeToJsonElement(it) }
        request.config.toolConfig?.let { this["tool_config"] = adkJson.encodeToJsonElement(it) }
        if (cacheContentsCount > 0 && request.contents.isNotEmpty()) {
          val count = minOf(cacheContentsCount, request.contents.size)
          this["cached_contents"] = adkJson.encodeToJsonElement(request.contents.take(count))
        }
      }
    return sha256Hex(JsonObject(fields).sortedKeys().toString()).take(FINGERPRINT_LENGTH)
  }

  /**
   * Returns a copy with every nested [JsonObject]'s keys sorted alphabetically (array element order
   * is preserved). Objects with the same entries then serialize identically regardless of the order
   * their keys were inserted, so the fingerprint is stable across equivalent requests.
   */
  private fun JsonElement.sortedKeys(): JsonElement =
    when (this) {
      is JsonObject ->
        JsonObject(entries.sortedBy { it.key }.associate { (k, v) -> k to v.sortedKeys() })
      is JsonArray -> JsonArray(map { it.sortedKeys() })
      else -> this
    }

  /** Creates a new cache when the previous request was large enough, otherwise returns `null`. */
  private suspend fun createNewCacheWithContents(
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
    // `tokenCount` covers the whole previous prompt, but the cache stores only the prefix
    // (system instruction + tools + first N contents). Gate the model's documented floor on the
    // estimated prefix size so we never send a sub-minimum payload that Gemini would reject with
    // 400. Opaque models have no known floor, so the server stays authoritative.
    val cacheablePrefixTokens = estimateCacheablePrefixTokens(request, cacheContentsCount)
    val minimumTokens = minimumCacheTokens(modelName)
    if (minimumTokens != null && cacheablePrefixTokens < minimumTokens) {
      logger.info {
        "Cacheable prefix below Gemini minimum cache size " +
          "($cacheablePrefixTokens < $minimumTokens tokens)."
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

  /**
   * The explicit-cache token floor for a named Gemini model family, or `null` for opaque
   * tuned-model / endpoint IDs where the server remains authoritative.
   */
  private fun minimumCacheTokens(model: String): Int? {
    val bareName = model.substringAfterLast('/')
    return when {
      bareName.startsWith("gemini-2.5-") -> GEMINI_2_5_MIN_CACHE_TOKENS
      bareName.startsWith("gemini-3") -> GEMINI_3_MIN_CACHE_TOKENS
      else -> null
    }
  }

  /** Creates the cache via the [cacheClient] and returns its metadata. */
  private suspend fun createGeminiCache(
    request: LlmRequest,
    cacheContentsCount: Int,
  ): CacheMetadata {
    val cacheConfig = requireNotNull(request.cacheConfig) { "cacheConfig must be set." }
    val displayName = "adk-cache-${Clock.System.now().epochSeconds}-${cacheContentsCount}contents"

    val cacheName =
      cacheClient.create(
        CacheCreateRequest(
          model = modelName,
          contents = request.contents.take(cacheContentsCount).takeIf { it.isNotEmpty() },
          systemInstruction = request.config.systemInstruction,
          tools = request.config.tools,
          toolConfig = request.config.toolConfig,
          ttl = cacheConfig.ttl,
          displayName = displayName,
        )
      )
    val createdAt = Clock.System.now().toEpochMilliseconds()
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
  private suspend fun cleanupCache(cacheName: String) {
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
        request.config.copy(
          systemInstruction = null,
          tools = null,
          toolConfig = null,
          cachedContent = cacheName,
        ),
      contents = request.contents.drop(cacheContentsCount),
    )

  /** The inputs needed to create a Gemini cache, expressed in ADK common types. */
  data class CacheCreateRequest(
    val model: String,
    // `null` when the cache holds no contents (system instruction + tools only), matching the API's
    // distinction between an absent and an empty contents field.
    val contents: List<Content>?,
    val systemInstruction: Content?,
    val tools: List<Tool>?,
    val toolConfig: ToolConfig?,
    val ttl: Duration,
    val displayName: String,
  )

  /**
   * Abstraction over the cache backend so the manager can be faked in tests; the SDK's cache types
   * are final with internal constructors and cannot be faked directly. The SDK-backed
   * implementation is [GenaiCacheClient].
   */
  interface CacheClient {
    /** Creates a cache and returns its resource name. */
    suspend fun create(request: CacheCreateRequest): String

    suspend fun delete(name: String)
  }

  /**
   * Estimates the token count of the prefix that will actually be cached (system instruction,
   * tools, and the first [cacheContentsCount] contents). The only accurate count available is
   * [LlmRequest.cacheableContentsTokenCount], which covers the whole previous prompt, so this
   * scales it by the prefix's estimated character share of the request.
   */
  @OptIn(FrameworkInternalApi::class)
  private fun estimateCacheablePrefixTokens(request: LlmRequest, cacheContentsCount: Int): Int {
    val fullTokens = request.cacheableContentsTokenCount ?: return 0
    if (fullTokens == 0) return 0
    val fullEstimate = estimateRequestTokens(request, null)
    // No text to estimate from (e.g. binary-only parts): trust the accurate full count.
    if (fullEstimate <= 0) return fullTokens
    val prefixEstimate = estimateRequestTokens(request, cacheContentsCount)
    val ratio = minOf(1.0, prefixEstimate.toDouble() / fullEstimate)
    return (fullTokens * ratio).toInt()
  }

  /**
   * Rough character-based token estimate (~4 chars/token) for the request, or its cacheable prefix
   * when [cacheContentsCount] is non-null. Always counts system instruction and tools.
   */
  @OptIn(FrameworkInternalApi::class)
  private fun estimateRequestTokens(request: LlmRequest, cacheContentsCount: Int?): Int {
    var totalChars = 0
    request.config.systemInstruction?.parts?.forEach { totalChars += it.text?.length ?: 0 }
    request.config.tools?.let { tools ->
      for (tool in tools) {
        totalChars += adkJson.encodeToString(Tool.serializer(), tool).length
      }
    }
    val contents =
      if (cacheContentsCount != null) request.contents.take(cacheContentsCount)
      else request.contents
    for (content in contents) {
      for (part in content.parts) {
        totalChars += part.text?.length ?: 0
      }
    }
    return totalChars / CHARS_PER_TOKEN
  }

  private companion object {
    private val logger = LoggerFactory.getLogger(GeminiContextCacheManager::class)

    // Named Gemini model families have documented explicit-cache token floors; opaque tuned-model
    // and endpoint IDs leave the floor to the server.
    private const val GEMINI_2_5_MIN_CACHE_TOKENS = 2048
    private const val GEMINI_3_MIN_CACHE_TOKENS = 4096

    private const val FINGERPRINT_LENGTH = 16

    // Rough heuristic for estimating tokens from character length.
    private const val CHARS_PER_TOKEN = 4
  }
}
