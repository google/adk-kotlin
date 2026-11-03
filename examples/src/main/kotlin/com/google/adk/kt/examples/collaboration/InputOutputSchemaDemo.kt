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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.examples.collaboration

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `input_output_schema` sample: a weather agent with an input and an output
 * schema runs in single-turn mode, so it is offered to its parent as a tool that takes a city and
 * answers with structured weather information.
 */
object InputOutputSchemaDemo {

  /**
   * Builds the weather concierge, with both agents on [model]. Run it as a node, with `App(rootNode
   * = ...)`, since its sub-agent sets a mode.
   */
  fun create(model: Model): LlmAgent =
    LlmAgent(
      name = "root_agent",
      model = model,
      instruction =
        Instruction(
          "You are a helpful weather concierge assistant. Use the weather_agent tool to get" +
            " weather information for the user's city, and then answer the user in a friendly" +
            " manner."
        ),
      subAgents = listOf(weatherAgent(model)),
    )
}

/**
 * Asks about three cities, each in its own session, and prints each message and tool call. Needs
 * `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val root =
    InputOutputSchemaDemo.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "input_output_schema", rootNode = root))
  val questions =
    listOf(
      "What is the weather in San Jose?",
      "Can you check the weather for Cupertino?",
      "What is the weather in Tokyo?",
    )
  for ((index, question) in questions.withIndex()) {
    println("\n=== $question")
    val message = Content.fromText(Role.USER, question)
    runner.runAsync(userId = "user", sessionId = "session_$index", newMessage = message).collect {
      event ->
      val text = event.contentText(" ")
      if (text.isNotBlank()) println("[${event.author}] $text")
      for (call in event.functionCalls()) {
        println("[${event.author}] calls ${call.name}(${call.args})")
      }
    }
  }
}

private fun weatherAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "weather_agent",
    model = model,
    // Runs as a tool for the parent, with the schemas as its parameters and answer.
    mode = AgentMode.SINGLE_TURN,
    // No transfer_to_agent tool, so the agent answers instead of handing the request back.
    disallowTransferToParent = true,
    inputSchema = CITY_QUERY_SCHEMA,
    outputSchema = WEATHER_INFO_SCHEMA,
    instruction =
      Instruction(
        """
        Provide weather information for the requested city.

        For San Jose, return temperature: 26 C, conditions: Sunny.
        For Cupertino, return temperature: 16 C, conditions: Foggy.
        For any other city, return temperature: unknown, conditions: unknown.
        """
          .trimIndent()
      ),
  )

/** The weather agent's input, with the fields of adk-python's `CityQuery` model. */
private val CITY_QUERY_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "city" to
          Schema(
            type = Type.STRING,
            description = "The name of the city to query weather for, e.g. San Jose",
          )
      ),
    required = listOf("city"),
  )

/** The weather agent's answer, with the fields of adk-python's `WeatherInfo` model. */
private val WEATHER_INFO_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "temperature" to Schema(type = Type.STRING, description = "The temperature in Celsius"),
        "conditions" to
          Schema(type = Type.STRING, description = "The weather condition, e.g. Sunny"),
      ),
    required = listOf("temperature", "conditions"),
  )
