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

package com.google.adk.kt.tools

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * A tool that loads artifacts and adds them to the session.
 *
 * This tool informs the model about available artifacts and provides their content when requested
 * by the model through a function call.
 */
class LoadArtifactsTool :
  BaseTool(
    name = "load_artifacts",
    description = """Loads artifacts into the session for this request.""",
  ) {
  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf("artifact_names" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))),
        ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val artifactNames = args.getArtifactNamesList()
    return mapOf(
      "artifact_names" to artifactNames,
      "status" to
        "artifact contents temporarily inserted and removed. to access these artifacts, call load_artifacts tool again.",
    )
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    var newRequest = super.processLlmRequest(toolContext, llmRequest)
    newRequest = appendInitialInstructionsToLlmRequest(toolContext, newRequest)
    newRequest = processLoadArtifactsFunctionCall(toolContext, newRequest)
    return newRequest
  }

  private suspend fun appendInitialInstructionsToLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val artifactNames = toolContext.listArtifacts()
    if (artifactNames.isEmpty()) {
      return llmRequest
    }

    // Tell the model about the available artifacts.
    val artifactNamesJson = "[" + artifactNames.joinToString(", ") { "\"$it\"" } + "]"
    val instructions =
      """
      You have a list of artifacts:
        $artifactNamesJson
      When the user asks questions about any of the artifacts, you should call the
      `load_artifacts` function to load the artifact. Always call load_artifacts
      before answering questions related to the artifacts, regardless of whether the
      artifacts have been loaded before. Do not depend on prior answers about the
      artifacts.
    """
        .trimIndent()
    return llmRequest.appendInstructions(Content(parts = listOf(Part(text = instructions))))
  }

  private suspend fun processLoadArtifactsFunctionCall(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    // Attach the content of the artifacts if the model requests them.
    val lastContent = llmRequest.contents.lastOrNull() ?: return llmRequest
    if (lastContent.parts.isEmpty()) {
      return llmRequest
    }

    val functionResponse = lastContent.parts.first().functionResponse ?: return llmRequest
    if (functionResponse.name != "load_artifacts") {
      return llmRequest
    }

    var newRequest = llmRequest
    for (artifactName in functionResponse.response.getArtifactNamesList()) {
      val artifact = loadArtifact(toolContext, artifactName)
      if (artifact == null) {
        continue
      }
      // TODO: Convert artifacts with unsupported MIME types to text, as Python ADK does.
      newRequest =
        newRequest.appendContent(
          Content(
            role = Role.USER,
            parts = listOf(Part(text = "Artifact $artifactName is:"), artifact),
          )
        )
    }
    return newRequest
  }

  private suspend fun loadArtifact(toolContext: ToolContext, artifactName: String): Part? {
    // Try session-scoped first (default behavior)
    val defaultScopedArtifact = toolContext.loadArtifact(artifactName)
    if (defaultScopedArtifact != null) {
      return defaultScopedArtifact
    }

    // If not found and name doesn't already have user: prefix,
    // try cross-session artifacts with user: prefix.
    if (!artifactName.startsWith("user:")) {
      return toolContext.loadArtifact("user:$artifactName")
    }

    return null
  }

  companion object {
    private fun Map<String, Any?>.getArtifactNamesList(): List<String> {
      return (this["artifact_names"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    }
  }
}
