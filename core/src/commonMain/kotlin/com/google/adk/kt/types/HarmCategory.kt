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

/** The category of a harm that a [SafetySetting] applies to. */
@Serializable
enum class HarmCategory {
  /** The harm category is unspecified. */
  HARM_CATEGORY_UNSPECIFIED,

  /** Harassment content. */
  HARM_CATEGORY_HARASSMENT,

  /** Hate speech and content. */
  HARM_CATEGORY_HATE_SPEECH,

  /** Sexually explicit content. */
  HARM_CATEGORY_SEXUALLY_EXPLICIT,

  /** Dangerous content. */
  HARM_CATEGORY_DANGEROUS_CONTENT,

  /** Content that may harm civic integrity. */
  HARM_CATEGORY_CIVIC_INTEGRITY,

  /** Hate content in generated images. */
  HARM_CATEGORY_IMAGE_HATE,

  /** Dangerous content in generated images. */
  HARM_CATEGORY_IMAGE_DANGEROUS_CONTENT,

  /** Harassment content in generated images. */
  HARM_CATEGORY_IMAGE_HARASSMENT,

  /** Sexually explicit content in generated images. */
  HARM_CATEGORY_IMAGE_SEXUALLY_EXPLICIT,

  /** Jailbreak content. */
  HARM_CATEGORY_JAILBREAK,
}
