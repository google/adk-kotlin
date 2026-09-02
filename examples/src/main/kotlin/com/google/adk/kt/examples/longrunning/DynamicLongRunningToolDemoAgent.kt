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

package com.google.adk.kt.examples.longrunning

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

private const val NAVIGATE_TO_TOOL = "navigate_to"

/**
 * Destinations the device already has cached, so [NavigateToTool] can answer them synchronously.
 */
private val ON_DEVICE_FAVORITES = setOf("home", "the office")

/**
 * Runnable demo of a single [BaseTool.isLongRunning] tool that decides *per call* whether to answer
 * now (return a value) or defer for external input (return `Unit`).
 *
 * [NavigateToTool] returns a real value for a cached favorite -- so the agent replies in one turn
 * -- and returns `Unit` ("no response yet") for an unknown place, so the turn ends awaiting the
 * device's result and a later `runAsync` delivers it as a `FunctionResponse`. This demo uses a
 * non-resumable app.
 */
fun main() = runBlocking {
  val model = ScriptedNavPlannerModel()
  val agent =
    LlmAgent(
      name = "nav_planner_agent",
      model = model,
      instruction = Instruction("Help the driver navigate. Use $NAVIGATE_TO_TOOL to reroute them."),
      tools = listOf(NavigateToTool()),
    )
  val runner = InMemoryRunner(agent = agent)

  println("=== Dynamic long-running tool demo (non-resumable) ===")
  println("One long-running tool decides per call whether to answer now or defer for the device.")

  // Scenario A: a cached favorite -- the tool returns a value and the agent answers in one turn.
  println("\nUser > Navigate to $REQUESTED_DESTINATION.")
  val turnA =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = Content.fromText(Role.USER, "Navigate to $REQUESTED_DESTINATION."),
      )
      .toList()
  printEvents("scenario A (cached favorite -> value returned, no pause)", turnA)
  println(
    "   model invocations: ${model.invocations} (tool call + summary); " +
      "unresolved long-running call: ${turnA.unresolvedLongRunningCall()?.name ?: "none"}"
  )

  // Scenario B: an unknown place -- the tool returns `Unit`, so the turn defers for the device.
  val unknownDestination = "742 Maple Street"
  println("\nUser > Navigate to $unknownDestination.")
  val turnB =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = Content.fromText(Role.USER, "Navigate to $unknownDestination."),
      )
      .toList()
  printEvents("scenario B (unknown place -> Unit returned, turn defers)", turnB)

  val pending = turnB.unresolvedLongRunningCall()
  if (pending == null) {
    println("No deferred call was produced; nothing to resume.")
    return@runBlocking
  }
  println("   deferred on ${pending.name} (callId=${pending.id}); waiting for the device.")

  println("[app] device geocoded the address and confirmed; returning the result to the agent.")
  val turnC =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = deviceGeocodeResult(pending),
      )
      .toList()
  printEvents("scenario B resume (device result delivered)", turnC)

  val session =
    runner.sessionService.getSession(SessionKey("InMemoryRunner", DEMO_USER_ID, DEMO_SESSION_ID))
  println("\nStored session now has ${session?.events?.size ?: 0} events.")
}

/**
 * A long-running tool that answers cached favorites synchronously and defers everything else.
 *
 * Returning a non-`Unit` value emits a function response the model summarizes in the same turn;
 * returning `Unit` suppresses that response so the turn ends on the long-running call and the
 * device's real result is injected later to resume it.
 */
private class NavigateToTool :
  BaseTool(
    name = NAVIGATE_TO_TOOL,
    description =
      "Reroute the driver. Cached favorites apply instantly; unknown places need the device to " +
        "geocode and confirm.",
    isLongRunning = true,
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              DESTINATION_ARG to
                Schema(type = Type.STRING, description = "Where to reroute the driver.")
            ),
          required = listOf(DESTINATION_ARG),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val destination = (args[DESTINATION_ARG] as? String)?.trim().orEmpty()
    return if (destination.lowercase() in ON_DEVICE_FAVORITES) {
      // Fast path: the route is already on the device, so respond now with a real value.
      println("   [backend] '$destination' is a cached favorite; rerouting immediately.")
      mapOf("status" to "rerouted", "destination" to destination, "source" to "on_device_cache")
    } else {
      // Slow path: dispatch to the device and defer by returning `Unit` (no response yet).
      println("   [backend] '$destination' is unknown; asking the device to geocode and confirm.")
      Unit
    }
  }
}

/**
 * A deterministic scripted [Model] so the demo runs without an API key: it requests
 * [NavigateToTool] for a user message and returns a plain-text confirmation once a tool result (or
 * the resumed device result) is present. Swap in a real `Gemini(name = ...)` model to drive the
 * same flow with an LLM.
 */
private class ScriptedNavPlannerModel : Model {
  override val name: String = "scripted-nav-planner-model"

  /** Number of times the model has been invoked, to show the tool-call-vs-summary contrast. */
  var invocations: Int = 0
    private set

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    invocations++
    val lastParts = request.contents.lastOrNull()?.parts.orEmpty()
    val toolResult = lastParts.firstNotNullOfOrNull { it.functionResponse }
    val response =
      if (toolResult != null) {
        // A cached hit or the device's resumed result is in -- confirm to the driver.
        LlmResponse(
          content = Content.fromText(Role.MODEL, "You're rerouted: ${toolResult.response}")
        )
      } else {
        // A new user request -- call the tool with the destination named in the message.
        val destination = destinationFrom(lastParts.firstNotNullOfOrNull { it.text }.orEmpty())
        LlmResponse(
          content =
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(
                    functionCall =
                      FunctionCall(
                        name = NAVIGATE_TO_TOOL,
                        args = mapOf(DESTINATION_ARG to destination),
                        id = "nav-call-$invocations",
                      )
                  )
                ),
            )
        )
      }
    return flowOf(response)
  }
}

/** Extracts the destination that follows "to " in the driver's message. */
private fun destinationFrom(text: String): String =
  text.substringAfter(" to ", missingDelimiterValue = text).trim().trimEnd('.', '!', '?')

/** The device's geocoded result for a deferred [NavigateToTool] call, injected on resume. */
private fun deviceGeocodeResult(deferredCall: FunctionCall): Content =
  Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              name = deferredCall.name,
              id = deferredCall.id,
              response =
                mapOf(
                  "status" to "rerouted",
                  "destination" to (deferredCall.args[DESTINATION_ARG] ?: "<unknown>"),
                  "eta_minutes" to 23,
                  "source" to "device_geocode",
                ),
            )
        )
      ),
  )

/**
 * Returns a long-running [FunctionCall] in these events that has no matching function response yet
 * -- i.e. the tool returned `Unit` and the turn is deferred -- or null when every long-running call
 * already got a value back.
 */
private fun List<Event>.unresolvedLongRunningCall(): FunctionCall? {
  val answered = flatMap { it.functionResponses() }.mapNotNull { it.id }.toSet()
  return firstNotNullOfOrNull { event ->
    event.functionCalls().firstOrNull {
      it.id != null && it.id in event.longRunningToolIds && it.id !in answered
    }
  }
}
