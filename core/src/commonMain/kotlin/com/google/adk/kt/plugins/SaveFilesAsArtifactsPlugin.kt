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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.Part
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmOverloads
import kotlin.math.round

/**
 * Saves files attached to user messages as artifacts, replacing each with a placeholder that names
 * the artifact so agents can load it later, such as with
 * [LoadArtifactsTool][com.google.adk.kt.tools.LoadArtifactsTool]. Each file is saved under its
 * [display name][com.google.adk.kt.types.Blob.displayName], where a `user:` prefix shares it across
 * the user's sessions, or under a generated name if it has no display name; files over 20 MB are
 * replaced with an error note instead. Mirrors Python ADK's `SaveFilesAsArtifactsPlugin`.
 *
 * @param attachFileReference Whether to also attach a [FileData] reference to each saved file when
 *   the artifact service stores it at a URI the model can read (`gs`, `https` or `http`).
 * @property name The unique name of the plugin instance.
 */
class SaveFilesAsArtifactsPlugin
@JvmOverloads
constructor(
  private val attachFileReference: Boolean = true,
  override val name: String = "save_files_as_artifacts_plugin",
) : Plugin {

  private val pendingDeltaKey = "$name:pending_delta"

  @OptIn(FrameworkInternalApi::class)
  override suspend fun onUserMessage(
    invocationContext: InvocationContext,
    userMessage: Content,
  ): Content {
    val artifactService = invocationContext.artifactService
    if (artifactService == null) {
      logger.warn { "Artifact service is not set. SaveFilesAsArtifactsPlugin will not be enabled." }
      return userMessage
    }

    val newParts = mutableListOf<Part>()
    val savedVersions = mutableMapOf<String, Int>()
    var modified = false
    for ((index, part) in userMessage.parts.withIndex()) {
      val inlineData = part.inlineData
      if (inlineData == null) {
        newParts += part
        continue
      }
      val fileName =
        inlineData.displayName?.ifEmpty { null }
          ?: "artifact_${invocationContext.invocationId}_$index"
      val size = inlineData.data?.size ?: 0
      if (size > MAX_FILE_SIZE_BYTES) {
        logger.warn { "The file in part $index is $size bytes, over the limit; it was not saved." }
        newParts +=
          Part(
            text =
              "[Upload Error: File $fileName (${formatMegabytes(size)} MB) exceeds the maximum " +
                "supported size of ${MAX_FILE_SIZE_MB}MB. Please upload a smaller file.]"
          )
        modified = true
        continue
      }
      val saved =
        try {
          save(artifactService, invocationContext.session.key, fileName, part)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // The exception message can contain the user-chosen file name, so log only the type.
          logger.error { "Failed to save the file in part $index (${e::class.simpleName})." }
          newParts += part
          continue
        }
      newParts += Part(text = "[Uploaded Artifact: \"$fileName\"]")
      saved.reference?.let { newParts += it }
      saved.version?.let { savedVersions[fileName] = it }
      modified = true
    }

    if (!modified) return userMessage
    if (savedVersions.isNotEmpty()) {
      // Unlike session state, which Python uses, per-invocation data leaves no entry behind.
      invocationContext.frameworkData.callbackContextData[pendingDeltaKey] =
        PendingDelta(savedVersions)
    }
    return userMessage.copy(parts = newParts)
  }

  /**
   * Reports the versions saved from the user message on the event of the first agent to run, since
   * a plugin cannot add actions to the user's own event.
   */
  @OptIn(FrameworkInternalApi::class)
  override suspend fun beforeAgent(
    context: CallbackContext
  ): CallbackChoice<EventActions, Content> {
    val pending =
      context.callbackContextData.remove(pendingDeltaKey) as? PendingDelta
        ?: return CallbackChoice.Continue(EventActions())
    return CallbackChoice.Continue(EventActions(artifactDelta = pending.versions.toMutableMap()))
  }

  /**
   * Saves [part] under [fileName]. When [attachFileReference] is enabled, this uses
   * [ArtifactService.saveAndReloadArtifact] to get the stored file's URI and then reads back the
   * latest version, since the service cannot look up the URI of a given version.
   */
  private suspend fun save(
    service: ArtifactService,
    sessionKey: SessionKey,
    fileName: String,
    part: Part,
  ): SavedFile {
    if (!attachFileReference) {
      return SavedFile(service.saveArtifact(sessionKey, fileName, part), reference = null)
    }
    val stored = service.saveAndReloadArtifact(sessionKey, fileName, part).fileData
    val uri = stored?.fileUri
    val reference =
      if (uri != null && isModelAccessibleUri(uri)) {
        Part(
          fileData =
            FileData(
              mimeType = part.inlineData?.mimeType?.ifEmpty { null } ?: stored.mimeType,
              displayName = fileName,
              fileUri = uri,
            )
        )
      } else {
        null
      }
    // The file is saved by now, so failing to read its version only leaves it out of the delta.
    val version =
      try {
        service.listVersions(sessionKey, fileName).maxOrNull()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn { "Saved a file but could not list its versions (${e::class.simpleName})." }
        return SavedFile(version = null, reference = reference)
      }
    if (version == null) logger.warn { "Saved a file but the service listed no versions of it." }
    return SavedFile(version, reference)
  }

  private data class SavedFile(val version: Int?, val reference: Part?)

  private data class PendingDelta(val versions: Map<String, Int>)

  companion object {
    private const val BYTES_PER_MB = 1024 * 1024

    // The Gemini API's size limit for inline data.
    private const val MAX_FILE_SIZE_MB = 20
    private const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * BYTES_PER_MB

    // The URI schemes models can fetch: Vertex reads `gs`, hosted endpoints `https` and `http`.
    private val MODEL_ACCESSIBLE_URI_SCHEMES = setOf("gs", "https", "http")

    private val logger = LoggerFactory.getLogger(SaveFilesAsArtifactsPlugin::class)

    private fun isModelAccessibleUri(uri: String): Boolean =
      uri.substringBefore(':', missingDelimiterValue = "").lowercase() in
        MODEL_ACCESSIBLE_URI_SCHEMES

    /**
     * Formats [bytes] in megabytes to two decimal places, rounding half to even like Python's
     * `:.2f`.
     */
    private fun formatMegabytes(bytes: Int): String {
      val hundredths = round(bytes * 100.0 / BYTES_PER_MB).toInt()
      return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
  }
}
