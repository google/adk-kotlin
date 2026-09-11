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

package com.google.adk.kt.artifacts

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Metadata describing one version of an artifact.
 *
 * A version number on its own says only that the version exists; this record is what a caller reads
 * to learn where the bytes are, what they are, and what was attached when they were saved.
 *
 * @property version Monotonically increasing identifier for the artifact version.
 * @property canonicalUri URI referencing the persisted payload, in whatever scheme the store that
 *   wrote it uses.
 * @property customMetadata Metadata supplied by the caller that saved this version.
 * @property createTime When the version record was created. An [Instant], like every other
 *   timestamp on this SDK's public surface; a store converts to whatever its own format holds.
 * @property mimeType Media type of the payload, or `null` when the store does not know it.
 */
data class ArtifactVersion(
  val version: Int,
  val canonicalUri: String,
  val customMetadata: Map<String, Any?> = emptyMap(),
  val createTime: Instant = Clock.System.now(),
  val mimeType: String? = null,
)
