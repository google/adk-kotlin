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
import com.google.adk.kt.platform.formatCompactTimestamp
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

    // Python's %Y%m%d_%H%M%S in the local time zone.
    val timestamp = formatCompactTimestamp(Clock.System.now())
    var unnamedCount = 0
    val parts =
      content.parts.map { part ->
        val image = part.inlineData?.takeIf { it.isImage() } ?: return@map part
        val fileName =
          sanitizeDisplayName(image.displayName)
            ?: run {
              unnamedCount++
              // A second unnamed image in the same response would overwrite the first in Python.
              val suffix = if (unnamedCount > 1) "_$unnamedCount" else ""
              "$timestamp$suffix.${image.fileExtension()}"
            }
        val unused =
          context.saveArtifact(
            fileName,
            Part(inlineData = Blob(mimeType = image.mimeType, data = image.data)),
          )
        part.copy(inlineData = null, text = "Saved as artifact: $fileName. ")
      }
    // As in ADK Python, the saved artifacts go on their own event, ahead of the model's response.
    emitEvent(
      Event(
        invocationId = context.invocationId,
        author = context.agentName,
        branch = context.branch,
        actions = context.actions.snapshot(),
      )
    )
    return response.copy(content = content.copy(parts = parts))
  }

  private fun Blob.isImage(): Boolean = mimeType?.startsWith("image/") == true

  /** The subtype of an `image/<subtype>` MIME type, such as `png`. */
  private fun Blob.fileExtension(): String = mimeType.orEmpty().substringAfter('/')

  /**
   * Returns a safe single-segment session-scoped artifact name from a model-supplied [displayName],
   * or `null` when nothing usable remains so the caller falls back to a generated name.
   */
  private fun sanitizeDisplayName(displayName: String?): String? {
    var name = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null
    while (name.startsWith("user:", ignoreCase = true)) {
      name = name.substring("user:".length).trim()
    }
    val stripped = name.trimEnd(' ', '.')
    if (
      stripped.isEmpty() ||
        stripped.any { it.code < 0x20 || it in INVALID_FILENAME_CHARS } ||
        stripped.equals("metadata.json", ignoreCase = true) ||
        stripped.equals("versions", ignoreCase = true)
    ) {
      return null
    }
    return stripped
  }

  companion object {
    private const val INVALID_FILENAME_CHARS = "<>:\"|?*"
  }
}
