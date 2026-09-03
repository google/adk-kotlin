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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a [Model]. A Java subclass overrides [generateContentJava],
 * which returns a Reactive Streams [Publisher]; this base adapts it to the `Flow`-returning
 * [generateContent] contract that Java cannot express. Both the unary and streaming cases flow
 * through the one method, exactly as they do for a Kotlin [Model].
 */
@AdkJavaInteropApi
abstract class BasePublisherModel(final override val name: String) : Model {

  /**
   * Generates content for [request]. When [stream] is false the publisher emits exactly one
   * aggregated response; when true it may emit any number of `partial = true` responses followed by
   * one aggregated `partial = false` response.
   */
  @JvmSuppressWildcards
  protected abstract fun generateContentJava(
    request: LlmRequest,
    stream: Boolean,
  ): Publisher<LlmResponse>

  final override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    generateContentJava(request, stream).asFlow()
}
