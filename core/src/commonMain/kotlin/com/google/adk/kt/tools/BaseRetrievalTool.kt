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

package com.google.adk.kt.tools

import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * Base class for retrieval tools.
 *
 * It supplies the shared function declaration - a single `query` string parameter - that a concrete
 * retrieval tool can reuse while it implements [run] to fetch context for the query. Mirrors the
 * Python and Java ADK `BaseRetrievalTool`.
 */
abstract class BaseRetrievalTool(
  name: String,
  description: String,
  isLongRunning: Boolean = false,
) : BaseTool(name = name, description = description, isLongRunning = isLongRunning) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf("query" to Schema(type = Type.STRING, description = "The query to retrieve.")),
        ),
    )
}
