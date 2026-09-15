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

package com.google.adk.kt.memory

import com.google.adk.kt.clients.validateSegment

/**
 * Builds the full RAG corpus resource name from a bare [ragCorpus] id under [project]/[location].
 *
 * [ragCorpus] must be a bare id, not a full resource name: the project and location come only from
 * the caller. Each segment is validated before being interpolated into a request URL.
 */
internal fun normalizeRagCorpusName(ragCorpus: String, project: String, location: String): String {
  validateSegment(project, "project")
  validateSegment(location, "location")
  require(!ragCorpus.startsWith("projects/")) {
    "ragCorpus must be a bare corpus id, not a full resource name."
  }
  validateSegment(ragCorpus, "ragCorpus id")
  return "projects/$project/locations/$location/ragCorpora/$ragCorpus"
}
