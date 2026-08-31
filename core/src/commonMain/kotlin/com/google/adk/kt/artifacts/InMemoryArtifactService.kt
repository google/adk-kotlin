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
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Part
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A thread-safe in-memory implementation of the [ArtifactService].
 *
 * User-scoped artifacts (see [ArtifactService]) live in a store of their own, keyed by app and
 * user, so no session id can address them and none can be shadowed by a session that happens to
 * share their name; [listArtifactKeys] merges the two scopes and returns them sorted. A null
 * [SessionKey.id] is rejected for a session-scoped name, since there is no session to store it
 * under; user-scoped names and [listArtifactKeys] accept one and address the user scope alone.
 */
class InMemoryArtifactService : ArtifactService {

  private val mutex = Mutex()
  private val sessionArtifacts: MutableMap<SessionKey, MutableMap<String, MutableList<Part>>> =
    mutableMapOf()
  private val userArtifacts: MutableMap<UserKey, MutableMap<String, MutableList<Part>>> =
    mutableMapOf()

  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int =
    mutex.withLock {
      val versions = store(sessionKey, filename).getOrPut(filename) { mutableListOf() }
      versions.add(artifact)
      versions.size - 1
    }

  override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): Part? = mutex.withLock {
    val versions = existingStore(sessionKey, filename)?.get(filename) ?: return@withLock null

    if (versions.isEmpty()) {
      return@withLock null
    }

    if (version == null) {
      return@withLock versions.lastOrNull()
    }

    if (version >= 0 && version < versions.size) {
      versions[version]
    } else {
      null
    }
  }

  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> = mutex.withLock {
    val sessionKeys = sessionArtifacts[sessionKey]?.keys.orEmpty()
    val userKeys = userArtifacts[userKey(sessionKey)]?.keys.orEmpty()
    (sessionKeys + userKeys).sorted()
  }

  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {
    mutex.withLock { existingStore(sessionKey, filename)?.remove(filename) }
  }

  override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): Part {
    val unused = saveArtifact(sessionKey, filename, artifact)
    return artifact
  }

  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
    mutex.withLock {
      existingStore(sessionKey, filename)?.get(filename)?.indices?.toList() ?: emptyList()
    }

  /**
   * The one scoping decision, shared by [store] and [existingStore] so that a write and the read
   * that follows it can never disagree about which store owns [filename].
   */
  private fun isUserScoped(filename: String): Boolean = filename.startsWith(State.USER_PREFIX)

  private fun userKey(sessionKey: SessionKey): UserKey =
    UserKey(sessionKey.appName, sessionKey.userId)

  /** Returns the store owning [filename], creating it if this is the first artifact in it. */
  private fun store(
    sessionKey: SessionKey,
    filename: String,
  ): MutableMap<String, MutableList<Part>> =
    if (isUserScoped(filename)) {
      userArtifacts.getOrPut(userKey(sessionKey)) { mutableMapOf() }
    } else {
      sessionArtifacts.getOrPut(requireSession(sessionKey)) { mutableMapOf() }
    }

  /** Like [store], but for reads: never creates an empty store for a scope nothing has used. */
  private fun existingStore(
    sessionKey: SessionKey,
    filename: String,
  ): MutableMap<String, MutableList<Part>>? =
    if (isUserScoped(filename)) {
      userArtifacts[userKey(sessionKey)]
    } else {
      sessionArtifacts[requireSession(sessionKey)]
    }

  /** A session-scoped name needs a real session; storing it under a null id would strand it. */
  private fun requireSession(sessionKey: SessionKey): SessionKey {
    requireNotNull(sessionKey.id) { "SessionKey.id is required for session-scoped artifacts." }
    return sessionKey
  }

  /** Addresses the per-user store. A distinct type, so no [SessionKey] can collide with it. */
  private data class UserKey(val appName: String, val userId: String)
}
