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

import kotlinx.serialization.Serializable

/** Represents a GenAI tool definition. */
@Serializable
data class Tool(
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
   * The 1.2.0 constructor, which had no [codeExecution]. Kept so that code compiled against 1.2.0,
   * and Java code written against it, still links and compiles.
   */
  constructor(
    functionDeclarations: List<FunctionDeclaration>? = null,
    googleSearch: GoogleSearch? = null,
    googleMaps: GoogleMaps? = null,
    retrieval: Retrieval? = null,
    urlContext: UrlContext? = null,
  ) : this(
    functionDeclarations = functionDeclarations,
    googleSearch = googleSearch,
    googleMaps = googleMaps,
    retrieval = retrieval,
    urlContext = urlContext,
    codeExecution = null,
  )
}
