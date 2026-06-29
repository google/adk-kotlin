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
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Part

/**
 * An [ArtifactService] that forwards every operation to the [ToolContext]'s parent invocation
 * artifact service, ignoring any [SessionKey] passed by the caller.
 *
 * Used by [com.google.adk.kt.tools.AgentTool] so that a wrapped agent (running on its own isolated
 * session) reads and writes artifacts on the parent's artifact store. Saves additionally record the
 * new version into the parent [ToolContext]'s `actions.artifactDelta`, so the parent invocation's
 * event picks the save up automatically. Mirrors Python ADK 1.x `ForwardingArtifactService`.
 */
internal class ForwardingArtifactService(private val toolContext: ToolContext) : ArtifactService {

  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int =
    toolContext.saveArtifact(filename, artifact)

  override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ): Part {
    val version = toolContext.saveArtifact(filename, artifact)
    return toolContext.loadArtifact(filename, version)
      ?: throw IllegalStateException(
        "Artifact '$filename' (version $version) not found after save."
      )
  }

  override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): Part? = toolContext.loadArtifact(filename, version)

  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> =
    toolContext.listArtifacts()

  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {
    val service =
      toolContext.invocationContext.artifactService
        ?: throw IllegalStateException(
          "artifactService not configured on parent invocation; cannot delete artifact '$filename'."
        )
    service.deleteArtifact(toolContext.invocationContext.session.key, filename)
  }

  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> {
    val service =
      toolContext.invocationContext.artifactService
        ?: throw IllegalStateException(
          "artifactService not configured on parent invocation; cannot list versions of " +
            "artifact '$filename'."
        )
    return service.listVersions(toolContext.invocationContext.session.key, filename)
  }
}
