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
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Serializable

/** Represents a GenAI tool definition. */
@Serializable
data class Tool
@JvmOverloads
constructor(
  /** The function declarations associated with this tool. */
  val functionDeclarations: List<FunctionDeclaration>? = null,
  /** A google search tool. */
  val googleSearch: GoogleSearch? = null,
  /** A google maps tool. */
  val googleMaps: GoogleMaps? = null,
  /** A retrieval tool. */
  val retrieval: Retrieval? = null,
  /** A URL context tool. */
  val urlContext: UrlContext? = null,
  /** The model's built-in code-execution tool. */
  val codeExecution: ToolCodeExecution? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .functionDeclarations(functionDeclarations)
      .googleSearch(googleSearch)
      .googleMaps(googleMaps)
      .retrieval(retrieval)
      .urlContext(urlContext)
      .codeExecution(codeExecution)

  /**
   * Fluent builder for [Tool], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var functionDeclarations: List<FunctionDeclaration>? = null
    private var googleSearch: GoogleSearch? = null
    private var googleMaps: GoogleMaps? = null
    private var retrieval: Retrieval? = null
    private var urlContext: UrlContext? = null
    private var codeExecution: ToolCodeExecution? = null

    fun functionDeclarations(functionDeclarations: List<FunctionDeclaration>?): Builder = apply {
      this.functionDeclarations = functionDeclarations
    }

    fun googleSearch(googleSearch: GoogleSearch?): Builder = apply {
      this.googleSearch = googleSearch
    }

    fun googleMaps(googleMaps: GoogleMaps?): Builder = apply { this.googleMaps = googleMaps }

    fun retrieval(retrieval: Retrieval?): Builder = apply { this.retrieval = retrieval }

    fun urlContext(urlContext: UrlContext?): Builder = apply { this.urlContext = urlContext }

    fun codeExecution(codeExecution: ToolCodeExecution?): Builder = apply {
      this.codeExecution = codeExecution
    }

    fun build(): Tool =
      Tool(
        functionDeclarations = functionDeclarations,
        googleSearch = googleSearch,
        googleMaps = googleMaps,
        retrieval = retrieval,
        urlContext = urlContext,
        codeExecution = codeExecution,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
