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

package com.google.adk.kt.examples.sessions

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.VertexAiSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/** A single tool the agent can call, exposed to the model via the `@Tool` KSP processor. */
class WeatherTools {
  @Tool
  fun getWeather(@Param("The city to look up.") city: String): Map<String, Any> =
    mapOf(
      "city" to city,
      "temperatureCelsius" to Random.nextInt(-5, 35),
      "condition" to listOf("sunny", "cloudy", "rainy", "windy").random(),
    )
}

/**
 * Runs a tool-using agent whose session is persisted in the managed Vertex AI Session Service.
 *
 * A [VertexAiSessionService] is handed to an [InMemoryRunner] (only the session service is
 * Vertex-backed; artifacts and memory stay in-memory), so the user turn, the agent's `getWeather`
 * function call, the tool response, and the model's reply all round-trip through the managed
 * service. The example then reads the session back to show the persisted events.
 *
 * The Vertex service assigns the session id, so the session is created up front and its id is
 * reused for the run and the read-back.
 *
 * Authentication uses Application Default Credentials (`gcloud auth application-default login`).
 *
 * Environment variables:
 * - `GOOGLE_CLOUD_PROJECT` - GCP project id.
 * - `VERTEX_REASONING_ENGINE_ID` - the numeric reasoning-engine id. Passed to the service at
 *   construction (the session key's app name is just a label and is not parsed for the engine).
 * - `GOOGLE_CLOUD_LOCATION` - Vertex region for sessions (optional, defaults to `us-central1`).
 */
fun main() {
  runBlocking {
    val project = requireEnv("GOOGLE_CLOUD_PROJECT")
    val reasoningEngineId = requireEnv("VERTEX_REASONING_ENGINE_ID")
    val location =
      System.getenv("GOOGLE_CLOUD_LOCATION")?.takeUnless { it.isBlank() } ?: "us-central1"

    // Pin the reasoning engine at construction; the session key's app name is then just a label.
    val sessionService =
      VertexAiSessionService(
        project = project,
        location = location,
        reasoningEngineId = reasoningEngineId,
      )
    val appName = "weather-app"
    val agent =
      LlmAgent(
        name = "weather_agent",
        description = "Answers weather questions using the getWeather tool.",
        model = Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(project, "global")),
        instruction =
          Instruction("Use the getWeather tool to answer weather questions. Answer briefly."),
        tools = WeatherTools().generatedTools(),
      )
    val runner = InMemoryRunner(agent = agent, appName = appName, sessionService = sessionService)

    val userId = "demo-user"
    val sessionId = sessionService.createSession(SessionKey(appName, userId, id = null)).key.id!!

    runner
      .runAsync(
        userId = userId,
        sessionId = sessionId,
        newMessage = Content.fromText(Role.USER, "What's the weather in San Francisco?"),
      )
      .collect { event ->
        if (event.partial == true) return@collect
        val text = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
        if (text.isNotBlank()) println("${event.author} > $text")
      }

    val session = sessionService.getSession(SessionKey(appName, userId, sessionId))
    println("Session $sessionId now has ${session?.events?.size ?: 0} persisted event(s).")
  }

  exitProcess(0)
}

private fun requireEnv(name: String): String =
  System.getenv(name)?.takeUnless { it.isBlank() }
    ?: error("Environment variable $name is not set.")
