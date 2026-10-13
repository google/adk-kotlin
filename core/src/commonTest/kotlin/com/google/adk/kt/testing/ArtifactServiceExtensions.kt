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

package com.google.adk.kt.testing

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.SessionKey
import kotlin.test.assertNotNull

/** The bytes stored for the artifact [event] references, such as a live-audio recording. */
suspend fun ArtifactService.storedBytes(event: Event, key: SessionKey): ByteArray? {
  val reference = event.content?.parts?.single()?.fileData?.fileUri?.substringAfterLast('/')
  assertNotNull(reference, "the event references no artifact")
  // Honor the referenced revision, so an earlier recording is not read as a same-named later one.
  val filename = reference.substringBefore('#')
  val revision = reference.substringAfter('#', "").toIntOrNull()
  return loadArtifact(key, filename, revision)?.inlineData?.data
}
