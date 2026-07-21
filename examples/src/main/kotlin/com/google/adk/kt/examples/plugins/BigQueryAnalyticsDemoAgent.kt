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

package com.google.adk.kt.examples.plugins

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.plugins.agentanalytics.BigQueryAgentAnalyticsPlugin
import com.google.adk.kt.plugins.agentanalytics.BigQueryLoggerConfig
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Example agent demonstrating plugin usage with the [BigQueryAgentAnalyticsPlugin].
 *
 * Plugins allow intercepting agent lifecycles, logging telemetry, and integrating with external
 * services like Google Cloud BigQuery.
 *
 * In this demo:
 * 1. [BigQueryAgentAnalyticsPlugin] is configured with BigQuery project/dataset details from
 *    environment variables (`BIGQUERY_PROJECT_ID` and `BIGQUERY_DATASET_ID`).
 * 2. An [App] is created wrapping the root agent and the plugin list.
 * 3. [InMemoryRunner] executes the agent turn, and the plugin automatically logs an event when
 *    invocation starts and completes.
 */
object BigQueryAnalyticsDemoAgent {

  fun createApp(projectId: String, datasetId: String, tableName: String = "agent_events"): App {
    val plugin =
      BigQueryAgentAnalyticsPlugin(
        config =
          BigQueryLoggerConfig(projectId = projectId, datasetId = datasetId, tableName = tableName)
      )

    return App(
      appName = "BigQueryAnalyticsDemoApp",
      rootAgent = rootAgent,
      plugins = listOf(plugin),
    )
  }

  @JvmField
  val rootAgent: BaseAgent =
    LlmAgent(
      name = "analytics_assistant",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          "You are a helpful research assistant. Your interactions and events are logged to BigQuery for analytics."
        ),
      tools = listOf(GoogleSearchTool()),
    )
}

fun main() {
  val projectId = System.getenv("BIGQUERY_PROJECT_ID")
  val datasetId = System.getenv("BIGQUERY_DATASET_ID")
  val tableName = System.getenv("BIGQUERY_TABLE_NAME") ?: "agent_events"

  if (projectId.isNullOrBlank() || datasetId.isNullOrBlank()) {
    println(
      """
      |Missing required environment variables!
      |
      |Please set the following environment variables:
      |  export BIGQUERY_PROJECT_ID=<YOUR_GCP_PROJECT_ID>
      |  export BIGQUERY_DATASET_ID=<YOUR_BIGQUERY_DATASET_ID>
      |  export BIGQUERY_TABLE_NAME=<YOUR_TABLE_NAME> (optional, defaults to 'agent_events')
      |
      |Example:
      |  export BIGQUERY_PROJECT_ID=my-gcp-project
      |  export BIGQUERY_DATASET_ID=agent_analytics
      |  ./gradlew :google-adk-kotlin-examples:runBigQueryAnalyticsDemo
      """
        .trimMargin()
    )
    return
  }

  println("Starting BigQuery Analytics Demo Agent...")
  println("Target BigQuery table: ${projectId}.${datasetId}.${tableName}")

  val app = BigQueryAnalyticsDemoAgent.createApp(projectId, datasetId, tableName)
  val runner = InMemoryRunner(app = app)

  runBlocking {
    val events =
      runner
        .runAsync(
          userId = "user_123",
          sessionId = "session_${System.currentTimeMillis()}",
          newMessage =
            Content(
              role = Role.USER,
              parts = listOf(Part(text = "What are some of the key features of the Kotlin ADK?")),
            ),
        )
        .toList()

    println("Agent execution finished. Events collected: ${events.size}")
    for (event in events) {
      val text = event.content?.parts?.firstOrNull()?.text
      if (!text.isNullOrBlank()) {
        println("[${event.author}]: $text")
      }
    }
  }
}
