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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future

/**
 * Java-friendly wrapper around an [ArtifactService] that it owns. Each method calls the `suspend`
 * method of the same name and returns a [CompletableFuture] of its result, so Java callers never
 * pass a `Continuation`. Canceling a future cancels its call, and [close] cancels every call in
 * flight and closes the service.
 */
@AdkJavaInteropApi
class CompletableFutureArtifactService private constructor(private val delegate: ArtifactService) :
  AutoCloseable {

  // Runs each call as a coroutine that completes its future; canceled by close().
  @Suppress("GlobalCoroutineDispatchers")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The wrapped artifact service, for code that has dropped into Kotlin. */
  fun asKotlinArtifactService(): ArtifactService = delegate

  /** Calls [ArtifactService.saveArtifact]; the future completes with the saved version. */
  fun saveArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): CompletableFuture<Int> = scope.future { delegate.saveArtifact(sessionKey, filename, artifact) }

  /** Calls [ArtifactService.saveAndReloadArtifact]. */
  fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): CompletableFuture<Part> = scope.future {
    delegate.saveAndReloadArtifact(sessionKey, filename, artifact)
  }

  /**
   * Calls [ArtifactService.loadArtifact], which loads the latest version when [version] is `null`.
   * The future completes with `null` if there is no such artifact.
   */
  @JvmOverloads
  fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int? = null,
  ): CompletableFuture<Part?> = scope.future {
    delegate.loadArtifact(sessionKey, filename, version)
  }

  /** Calls [ArtifactService.listArtifactKeys]. */
  fun listArtifactKeys(sessionKey: SessionKey): CompletableFuture<List<String>> = scope.future {
    delegate.listArtifactKeys(sessionKey)
  }

  /** Calls [ArtifactService.deleteArtifact]. */
  fun deleteArtifact(sessionKey: SessionKey, filename: String): CompletableFuture<Void?> =
    scope.future {
      delegate.deleteArtifact(sessionKey, filename)
      null
    }

  /** Calls [ArtifactService.listVersions]. */
  fun listVersions(sessionKey: SessionKey, filename: String): CompletableFuture<List<Int>> =
    scope.future {
      delegate.listVersions(sessionKey, filename)
    }

  /**
   * Cancels calls in flight, then closes the service if it is [AutoCloseable]. Every later call
   * returns a canceled future.
   */
  override fun close() {
    scope.cancel()
    (delegate as? AutoCloseable)?.close()
  }

  companion object {
    /**
     * Wraps [artifactService] and takes ownership of it: [close] also closes it if it is
     * [AutoCloseable].
     */
    @JvmStatic
    fun of(artifactService: ArtifactService): CompletableFutureArtifactService =
      CompletableFutureArtifactService(artifactService)
  }
}
