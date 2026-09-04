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

/** Whether a [Param] is required in the generated tool schema. */
enum class Requiredness {
  /**
   * Infer it: the KSP path uses Kotlin nullability (a non-null, no-default parameter is required),
   * and the reflective path treats the parameter as required, since Java bytecode carries no
   * nullability.
   */
  AUTO,

  /** Always required. */
  REQUIRED,

  /** Always optional. */
  OPTIONAL,
}
