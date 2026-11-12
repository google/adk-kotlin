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

package com.google.adk.kt.models

import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/**
 * Returns this part without its thought signature. The model round-trips the signature through the
 * history, so a span would repeat its bytes on every later call.
 */
internal fun Part.withoutThoughtSignature(): Part =
  if (thoughtSignature == null) this else copy(thoughtSignature = null)

/**
 * Returns this content with each inline media part replaced by its MIME type and byte count, and
 * the other parts [withoutThoughtSignature], so a span stays small and readable.
 */
internal fun Content.withInlineDataSummarized(): Content =
  copy(
    parts =
      parts.map { part ->
        val blob = part.inlineData ?: return@map part.withoutThoughtSignature()
        val mimeType = blob.mimeType?.takeIf { it.isNotEmpty() } ?: "unknown"
        Part(text = "<inline_data: $mimeType, ${blob.data?.size ?: 0} bytes>")
      }
  )
