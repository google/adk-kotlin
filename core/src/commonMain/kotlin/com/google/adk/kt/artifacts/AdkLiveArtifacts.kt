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

import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Part

/** The directory live recordings are referenced under; no model can resolve these references. */
internal const val ADK_LIVE_DIR = "_adk_live"

/**
 * The reference to a saved live recording. As in ADK Python, it adds an [ADK_LIVE_DIR] segment that
 * the saved artifact's name does not have.
 */
internal fun adkLiveArtifactReference(key: SessionKey, filename: String, revision: Int): String =
  "artifact://${key.appName}/${key.userId}/${key.id}/$ADK_LIVE_DIR/$filename#$revision"

/**
 * Whether [this] references a saved live recording, as ADK Python's `_is_adk_live_artifact_part`.
 */
internal fun Part.isAdkLiveArtifact(): Boolean =
  fileData?.fileUri?.let { it.startsWith("artifact://") && "/$ADK_LIVE_DIR/" in it } == true
