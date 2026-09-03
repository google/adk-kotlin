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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Part
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await

/**
 * Java-friendly base for implementing an [ArtifactService]. The engine's methods are `suspend`; a
 * Java subclass returns [CompletableFuture]s instead. Return each future promptly and do any
 * blocking work inside it, not before returning it.
 */
@AdkJavaInteropApi
abstract class BaseFutureArtifactService : ArtifactService {

  final override suspend fun saveArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): Int = saveArtifactAsync(sessionKey, filename, artifact).await()

  final override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): Part = saveAndReloadArtifactAsync(sessionKey, filename, artifact).await()

  final override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): Part? = loadArtifactAsync(sessionKey, filename, version).await()

  final override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> =
    listArtifactKeysAsync(sessionKey).await()

  final override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {
    deleteArtifactAsync(sessionKey, filename).await()
  }

  final override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
    listVersionsAsync(sessionKey, filename).await()

  protected abstract fun saveArtifactAsync(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): CompletableFuture<Int>

  protected abstract fun loadArtifactAsync(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): CompletableFuture<Part?>

  protected abstract fun listArtifactKeysAsync(
    sessionKey: SessionKey
  ): CompletableFuture<List<String>>

  protected abstract fun deleteArtifactAsync(
    sessionKey: SessionKey,
    filename: String,
  ): CompletableFuture<Void?>

  protected abstract fun listVersionsAsync(
    sessionKey: SessionKey,
    filename: String,
  ): CompletableFuture<List<Int>>

  /** Saves an artifact and returns it, with `fileData` populated if the store provides it. */
  protected abstract fun saveAndReloadArtifactAsync(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): CompletableFuture<Part>
}
