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

import com.google.adk.kt.types.toGenaiSdk
import com.google.genai.kotlin.Caches
import com.google.genai.kotlin.types.CreateCachedContentConfig

/**
 * [GeminiContextCacheManager.CacheClient] backed by the GenAI SDK's `client.caches`.
 *
 * This is the single place that touches the GenAI SDK cache API, translating ADK common types to
 * and from the SDK so the manager itself stays decoupled from the SDK and can be faked in tests.
 */
internal class GenaiCacheClient(private val caches: Caches) :
  GeminiContextCacheManager.CacheClient {

  override suspend fun create(request: GeminiContextCacheManager.CacheCreateRequest): String {
    val config =
      CreateCachedContentConfig(
        contents = request.contents?.map { it.toGenaiSdk() },
        systemInstruction = request.systemInstruction?.toGenaiSdk(),
        tools = request.tools?.map { it.toGenaiSdk() },
        toolConfig = request.toolConfig?.toGenaiSdk(),
        ttl = request.ttl,
        displayName = request.displayName,
        httpOptions = request.httpOptions,
      )
    val cachedContent = caches.create(request.model, config)
    return cachedContent.name ?: throw IllegalStateException("Created cache has no resource name.")
  }

  override suspend fun delete(name: String) {
    val unused = caches.delete(name)
  }
}
