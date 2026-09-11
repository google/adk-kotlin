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
import kotlinx.serialization.json.JsonNames

/** Represents file data. */
@Serializable
data class FileData(
  /** The MIME type of the file. */
  @JsonNames("mime_type") val mimeType: String? = null,
  /** The display name of the file. */
  @JsonNames("display_name") val displayName: String? = null,
  /** The URI of the file. */
  @JsonNames("file_uri") val fileUri: String? = null,
)
