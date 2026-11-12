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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * What a [Model] supports, as it reports through [Model.capabilities].
 *
 * @property outputSchemaAndTools Whether the model can apply a response schema in a request that
 *   also offers tools. When false, an agent with an output schema and tools (including
 *   `transfer_to_agent`) gets its final answer through a `set_model_response` tool call instead.
 */
data class LlmCapabilities @JvmOverloads constructor(val outputSchemaAndTools: Boolean = false) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi fun toBuilder(): Builder = Builder().outputSchemaAndTools(outputSchemaAndTools)

  /**
   * Fluent builder for [LlmCapabilities], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var outputSchemaAndTools: Boolean = false

    fun outputSchemaAndTools(outputSchemaAndTools: Boolean): Builder = apply {
      this.outputSchemaAndTools = outputSchemaAndTools
    }

    fun build(): LlmCapabilities = LlmCapabilities(outputSchemaAndTools = outputSchemaAndTools)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
