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

package com.google.adk.kt.memory.dto

import kotlinx.serialization.Serializable

/**
 * JSON `metadata` part of the multipart `ragFiles:upload` request from
 * `vertex_rag_data_service.proto`.
 *
 * The `parent` corpus name travels in the URL path and the file bytes travel in the `file` part, so
 * the metadata part only needs to carry the [RagFileDto].
 */
@Serializable internal data class UploadRagFileMetadataDto(val ragFile: RagFileDto)

/** Wire-level `RagFile`: only the writable [displayName] is set on upload. */
@Serializable
internal data class RagFileDto(val displayName: String? = null, val description: String? = null)
