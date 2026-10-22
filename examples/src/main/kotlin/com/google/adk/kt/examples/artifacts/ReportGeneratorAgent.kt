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

package com.google.adk.kt.examples.artifacts

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.artifacts.GcsArtifactService
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** A service that allows the agent to save a generated report directly to the ArtifactService. */
class ReportGeneratingService {
  /**
   * Saves a generated markdown report to the artifact service.
   *
   * @param filename The desired filename for the report. Should end in .md
   * @param content The full markdown content of the generated report.
   */
  @Tool
  suspend fun saveReport(context: ToolContext, filename: String, content: String): String {
    val artifactService =
      context.invocationContext.artifactService
        ?: return "Failed: ArtifactService not configured in InvocationContext."

    val unused =
      artifactService.saveArtifact(
        sessionKey =
          SessionKey(
            appName = "ReportGeneratorApp",
            userId = context.invocationContext.session.key.userId,
            id = context.invocationContext.session.key.id!!,
          ),
        filename = filename,
        artifact = Part(inlineData = Blob(data = content.toByteArray(), mimeType = "text/markdown")),
      )

    println(">>> [SYSTEM] Report saved to artifact: $filename")
    return "Report saved successfully."
  }

  /**
   * Retrieves a previously saved markdown report from the artifact service.
   *
   * @param filename The filename to retrieve. Should end in .md
   */
  @Tool
  suspend fun readReport(context: ToolContext, filename: String): String {
    val artifactService =
      context.invocationContext.artifactService
        ?: return "Failed: ArtifactService not configured in InvocationContext."

    val artifact =
      artifactService.loadArtifact(
        sessionKey =
          SessionKey(
            appName = "ReportGeneratorApp",
            userId = context.invocationContext.session.key.userId,
            id = context.invocationContext.session.key.id!!,
          ),
        filename = filename,
      )

    if (artifact == null) {
      println(">>> [SYSTEM] Failed to read report: $filename (not found)")
      return "Report not found."
    }

    println(">>> [SYSTEM] Report read successfully: $filename")
    return "Report contents:\n" +
      (artifact.text ?: artifact.inlineData?.data?.decodeToString() ?: "[no content]")
  }
}

/**
 * Example agent demonstrating a common use case for the Artifact Service: Generated Reports.
 *
 * This example showcases:
 * 1. Defining a custom service using `@Tool` annotations that invokes the `ArtifactService` to save
 *    its output.
 * 2. An agent generating a report and using the tool to "persist" the data.
 * 3. The overarching application using [GcsArtifactService] as the backend storage provider.
 */
object ReportGeneratorAgent {
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "report_generator",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      instruction =
        Instruction(
          "You are a report generating assistant. When asked to write a report, gather the necessary info, format it perfectly, save it using the `save_report` tool, and then read it back using the `read_report` tool to verify the content you just saved."
        ),
      tools = ReportGeneratingService().generatedTools(),
    )
}

fun main() = runBlocking {
  val sessionId = java.util.UUID.randomUUID().toString()
  val artifactService =
    GcsArtifactService(
      bucketName =
        System.getenv("GCS_BUCKET_NAME")
          ?: error(
            "GCS_BUCKET_NAME environment variable not set. Please set it to run this example."
          ),
      storageClient =
        StorageOptions.getDefaultInstance().service
          ?: error("Failed to initialize Google Cloud Storage client."),
    )

  println("Starting Report Generator Agent...")

  val events =
    InMemoryRunner(agent = ReportGeneratorAgent.rootAgent, artifactService = artifactService)
      .runAsync(
        userId = "user_123",
        sessionId = sessionId,
        newMessage =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  text =
                    "Please write a short 2-paragraph report on the history of ADK (https://google.github.io/adk-docs/), save it to artifacts, and read it back to verify it."
                )
              ),
          ),
      )
      .toList()

  println("Agent execution complete. Events outputted: ${events.size}")
  for (event in events) {
    println("Event authored by ${event.author}: ${event.content?.parts?.firstOrNull()?.text}")
  }

  // Verify the artifact was actually saved via listArtifactKeys
  println(
    "Artifacts in GCS for session '$sessionId': " +
      artifactService.listArtifactKeys(
        SessionKey(appName = "ReportGeneratorApp", userId = "user_123", id = sessionId)
      )
  )
}
