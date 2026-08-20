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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import kotlin.time.Clock

/**
 * Saves the images that the model's built-in code execution returns as artifacts, replacing each
 * with a text part that names the artifact, so the image bytes stay out of the session history. It
 * acts only when the request enabled code execution, for example through a
 * [com.google.adk.kt.tools.BuiltInCodeExecutionTool], as ADK Python does for its built-in executor.
 */
internal class CodeExecutionImageProcessor : LlmResponseProcessor {

  override suspend fun process(
    context: CallbackContext,
    request: LlmRequest,
    response: LlmResponse,
    emitEvent: suspend (Event) -> Unit,
  ): LlmResponse {
    // A partial chunk is not stored, and the final response repeats its images, as in ADK Python.
    if (response.partial) return response
    val content = response.content ?: return response
    if (request.config.tools.orEmpty().none { it.codeExecution != null }) return response
    if (content.parts.none { it.inlineData?.isImage() == true }) return response
    // Checked first because saveArtifact's own error would name a file that the model chose.
    checkNotNull(context.artifactService) {
      "Saving code execution images needs an artifact service."
    }

    val timestamp = Clock.System.now().toEpochMilliseconds()
    var imageCount = 0
    val parts =
      content.parts.map { part ->
        val image = part.inlineData?.takeIf { it.isImage() } ?: return@map part
        imageCount++
        val fileName =
          image.displayName?.takeIf { it.isNotEmpty() }
            ?: "${timestamp}_$imageCount.${image.fileExtension()}"
        val unused =
          context.saveArtifact(
            fileName,
            Part(inlineData = Blob(mimeType = image.mimeType, data = image.data)),
          )
        part.copy(inlineData = null, text = "Saved as artifact: $fileName. ")
      }
    val invocation = context.invocationContext
    // As in ADK Python, the saved artifacts go on their own event, ahead of the model's response.
    emitEvent(
      Event(
        invocationId = invocation.invocationId,
        author = invocation.agent.name,
        branch = invocation.branch,
        actions = context.actions.snapshot(),
      )
    )
    return response.copy(content = content.copy(parts = parts))
  }

  private fun Blob.isImage(): Boolean = mimeType?.startsWith("image/") == true

  /** The subtype of an `image/<subtype>` MIME type, such as `png`. */
  private fun Blob.fileExtension(): String = mimeType.orEmpty().substringAfter('/')
}
