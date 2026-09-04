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
 * Annotates a parameter of a function annotated with [Tool] to provide explicit metadata.
 *
 * @property description An optional explicit description for the parameter. If not provided, the
 *   `@param` tag from the KDoc is used.
 * @property name An optional explicit name for the parameter. When set, both the KSP path and
 *   [com.google.adk.kt.interop.ReflectiveTools] use it as the schema name; when unset, the KSP path
 *   uses the Kotlin parameter name, while the reflective path requires it because Java does not
 *   retain parameter names in bytecode.
 * @property required Whether the parameter is required; see [Requiredness]. Honored by both paths,
 *   defaulting to [Requiredness.AUTO].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
  val description: String = "",
  val name: String = "",
  val required: Requiredness = Requiredness.AUTO,
)
