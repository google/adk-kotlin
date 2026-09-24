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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Serializable

/** Represents a function declaration for tool calling. */
@Serializable
data class FunctionDeclaration(
  /** The name of the function. */
  val name: String,
  /** A description of what the function does. */
  val description: String,
  /** The parameters required by this function. */
  val parameters: Schema? = null,
  /** The shape of the value this function returns, when the tool declares one. */
  val response: Schema? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder().name(name).description(description).parameters(parameters).response(response)

  /**
   * Fluent builder for [FunctionDeclaration], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var description: String? = null
    private var parameters: Schema? = null
    private var response: Schema? = null

    fun name(name: String): Builder = apply { this.name = name }

    fun description(description: String): Builder = apply { this.description = description }

    fun parameters(parameters: Schema?): Builder = apply { this.parameters = parameters }

    fun response(response: Schema?): Builder = apply { this.response = response }

    fun build(): FunctionDeclaration =
      FunctionDeclaration(
        name = checkNotNull(name) { "FunctionDeclaration.Builder requires name to be set." },
        description =
          checkNotNull(description) {
            "FunctionDeclaration.Builder requires description to be set."
          },
        parameters = parameters,
        response = response,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
