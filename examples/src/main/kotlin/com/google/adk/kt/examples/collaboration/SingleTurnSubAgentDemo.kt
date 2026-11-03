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
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
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

// Avoid gemini-3.8-flash, which often keeps calling check_phone_price instead of answering.
private const val MODEL_NAME = "gemini-4-argon"

/**
 * A port of adk-python's `single_turn_sub_agent` sample: a phone sales assistant turns the user's
 * request into structured preferences and calls a single-turn sub-agent, which checks prices with a
 * tool and answers with a structured recommendation without talking to the user. Unlike an
 * `AgentTool`, the sub-agent's tool calls stay in the session history.
 */
object SingleTurnSubAgentDemo {

  /**
   * Builds the sales assistant, with both agents on [model]. Run it as a node, with `App(rootNode =
   * ...)`, since its sub-agent sets a mode.
   */
  fun create(model: Model): LlmAgent =
    LlmAgent(
      name = "root_agent",
      model = model,
      instruction =
        Instruction(
          """
          You are a helpful phone sales associate.
          If the user is asking for a phone recommendation, use the `phone_recommender` to get a
          structured recommendation.
          Once the recommender finishes, present the model, price, and reason to the user in a
          friendly way.
          """
            .trimIndent()
        ),
      // A single-turn sub-agent is offered to the root as a tool that takes its input schema.
      subAgents = listOf(phoneRecommenderAgent(model)),
    )
}

/**
 * Runs one request for a recommendation and prints each message and tool call. Needs
 * `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val root =
    SingleTurnSubAgentDemo.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "single_turn_sub_agent", rootNode = root))
  val message =
    Content.fromText(
      Role.USER,
      "I need a phone mostly for gaming. I have about $1000 to spend, and I prefer large phones.",
    )
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
    for (call in event.functionCalls()) {
      println("[${event.author}] calls ${call.name}(${call.args})")
    }
  }
}

private fun phoneRecommenderAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "phone_recommender",
    model = model,
    description = "Recommends a Pixel phone based on preferences.",
    mode = AgentMode.SINGLE_TURN,
    inputSchema = USER_PREFERENCES_SCHEMA,
    outputSchema = PHONE_RECOMMENDATION_SCHEMA,
    // KSP generates CheckPhonePriceTool from the @Tool function PixelPrices.checkPhonePrice.
    tools = listOf(CheckPhonePriceTool(PixelPrices())),
    instruction =
      Instruction(
        """
        You are an expert Google Pixel hardware recommender.
        Based on the provided UserPreferences, recommend exactly one Pixel phone model.
        You must use the `check_phone_price` tool to find the exact current price of the model
        you are recommending before you finish your task.
        Only recommend these phones: Pixel 10a, Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL,
        Pixel 10 Pro Fold.
        """
          .trimIndent()
      ),
  )

/** The user's preferences, with the fields of adk-python's `UserPreferences` model. */
private val USER_PREFERENCES_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "budget" to Schema(type = Type.INTEGER, description = "The user's maximum budget in USD"),
        "primary_use" to
          Schema(
            type = Type.STRING,
            description =
              "What the user primarily uses their phone for (e.g., photography, gaming, basics)",
          ),
        "preferred_size" to
          Schema(type = Type.STRING, description = "Preferred phone size (e.g., small, large, any)"),
      ),
    required = listOf("budget", "primary_use", "preferred_size"),
  )

/** The recommendation, with the fields of adk-python's `PhoneRecommendation` model. */
private val PHONE_RECOMMENDATION_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "model_name" to Schema(type = Type.STRING),
        "price" to Schema(type = Type.NUMBER),
        "reason" to Schema(type = Type.STRING),
      ),
    required = listOf("model_name", "price", "reason"),
  )

/** A mock price list that the recommender checks through a tool. */
class PixelPrices {
  // Longest names first, since a shorter name like "Pixel 10" is part of longer ones.
  private val prices =
    mapOf(
      "Pixel 10 Pro Fold" to 1799.0,
      "Pixel 10 Pro XL" to 1199.0,
      "Pixel 10 Pro" to 999.0,
      "Pixel 10a" to 499.0,
      "Pixel 10" to 799.0,
    )

  /** Mock tool to check the current price of a Pixel phone model. */
  @Tool(name = "check_phone_price")
  fun checkPhonePrice(@Param("The Pixel model, such as Pixel 10 Pro") modelName: String): Double =
    prices.entries.firstOrNull { (key, _) -> modelName.contains(key, ignoreCase = true) }?.value
      ?: 799.0
}
