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

/**
 * Base interface for artifact services.
 *
 * A `filename` beginning with `user:` addresses an artifact shared by every session of the same
 * user; any other filename addresses one belonging to the session named by the [SessionKey].
 * Whether a service partitions by [SessionKey.appName], what a null [SessionKey.id] means, and
 * whether it honours the given key at all are implementation-defined; see the implementation.
 */
interface ArtifactService {

  /**
   * Saves an artifact.
   *
   * @param sessionKey identifies the user and session the operation is scoped to.
   * @param filename the artifact filename; a `user:` prefix addresses the user scope instead.
   * @param artifact the artifact
   * @return the revision ID (version) of the saved artifact.
   */
  suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int

  /**
   * Saves an artifact and returns it with fileData if available.
   *
   * @param sessionKey identifies the user and session the operation is scoped to.
   * @param filename the artifact filename; a `user:` prefix addresses the user scope instead.
   * @param artifact the artifact to save
   * @return the saved artifact with fileData if available.
   */
  suspend fun saveAndReloadArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Part

  /**
   * Gets an artifact.
   *
   * @param sessionKey identifies the user and session the operation is scoped to.
   * @param filename the artifact filename; a `user:` prefix addresses the user scope instead.
   * @param version Optional version number. If null, loads the latest version.
   * @return the artifact or null if not found
   */
  suspend fun loadArtifact(sessionKey: SessionKey, filename: String, version: Int? = null): Part?

  /**
   * Lists the filenames visible to a session: its own artifacts plus the user's `user:` ones.
   *
   * @param sessionKey identifies the user and session whose artifacts are listed.
   * @return the artifact filenames, with `user:` prefixes retained.
   */
  suspend fun listArtifactKeys(sessionKey: SessionKey): List<String>

  /**
   * Deletes an artifact.
   *
   * @param sessionKey identifies the user and session the operation is scoped to.
   * @param filename the artifact filename; a `user:` prefix addresses the user scope instead.
   */
  suspend fun deleteArtifact(sessionKey: SessionKey, filename: String)

  /**
   * Lists all the versions (as revision IDs) of an artifact.
   *
   * @param sessionKey identifies the user and session the operation is scoped to.
   * @param filename the artifact filename; a `user:` prefix addresses the user scope instead.
   * @return A list of integer version numbers
   */
  suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int>
}
