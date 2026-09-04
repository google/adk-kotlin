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

package com.google.adk.kt.annotations

/**
 * Annotates a function to be exposed as an executable tool by the Google ADK Kotlin. KSP will
 * generate a corresponding [com.google.adk.kt.tools.FunctionTool] wrapper for annotated functions.
 *
 * The generated wrapper converts the function's return value into a JSON-native [Map]: data
 * classes, enums, lists, maps, and nested structures are translated at compile time. A function may
 * therefore return a data class directly, unlike a hand-written [com.google.adk.kt.tools.BaseTool],
 * whose result must already be JSON-native.
 *
 * Note: this annotation shares its simple name with [com.google.adk.kt.types.Tool], the GenAI tool
 * definition data class. Files that need to reference both should use an import alias.
 *
 * Retained at runtime so the JVM-only [com.google.adk.kt.interop.ReflectiveTools] can read it via
 * reflection to build tools from Java methods, which the KSP path cannot process without the Kotlin
 * compiler.
 *
 * @property name An optional explicit name for the tool. If not provided, the function name is
 *   used.
 * @property description An optional explicit description. If not provided, the KDoc summary is
 *   used.
 * @property requireConfirmation If true, the tool execution requires user confirmation.
 * @property isLongRunning If true, indicates the tool returns a Pending state and resumes later.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
  val name: String = "",
  val description: String = "",
  val requireConfirmation: Boolean = false,
  val isLongRunning: Boolean = false,
)
