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

package com.google.adk.kt.workflow

import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * Adapts a node's input [schema] to an object-typed function parameter declaration, wrapping a
 * non-object schema under a single required property [key].
 */
internal class ToolParameters(val schema: Schema, key: String) {

  /** The wrapper property name, or `null` when [schema] is already an object schema. */
  val wrapperKey: String? = key.takeIf { schema.type != Type.OBJECT }

  /** The object-typed parameter schema to declare to the model. */
  val declaration: Schema =
    wrapperKey?.let {
      Schema(type = Type.OBJECT, properties = mapOf(it to schema), required = listOf(it))
    } ?: schema

  /** Extracts the raw node input from the tool call [args]. */
  fun valueOf(args: Map<String, Any?>): Any? = if (wrapperKey == null) args else args[wrapperKey]
}
