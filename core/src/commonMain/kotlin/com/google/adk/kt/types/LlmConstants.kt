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

/** Internal constants for LLM requests and responses. */
internal object LlmConstants {
  const val INLINE_DATA = "inline_data"
  const val FILE_DATA = "file_data"

  /** Keys used for mapping/logging LLM requests. */
  const val KEY_MODEL = "model"
  const val KEY_CONTENTS = "contents"
  const val KEY_CONFIG = "config"
  const val KEY_SYSTEM_INSTRUCTION = "systemInstruction"
}
