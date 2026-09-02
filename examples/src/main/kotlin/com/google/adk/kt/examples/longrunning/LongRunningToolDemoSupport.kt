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

import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
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

/**
 * Shared building blocks for the long-running tool demos in this package.
 *
 * They model a "client-side" action: a tool whose real work runs on the user's device, so the
 * backend agent dispatches the action and later resumes when the device returns the result. The
 * change-destination demos share this tool and scripted model; every demo reuses the constants and
 * event-printing helpers below.
 */
internal const val CHANGE_DESTINATION_TOOL = "change_destination"
internal const val DESTINATION_ARG = "destination"
internal const val REQUESTED_DESTINATION = "the office"
internal const val DEMO_USER_ID = "driver-1"
internal const val DEMO_SESSION_ID = "drive-session-1"

/**
 * A long-running tool standing in for an action that executes on the client (the user's device).
 *
 * Marking it [BaseTool.isLongRunning] tells the framework the real result arrives out-of-band. When
 * [respondImmediately] is true the tool returns a placeholder acknowledging the dispatch, which
 * answers the call so the model summarizes it; when false it returns `Unit` ("no response yet"),
 * suppressing the function-response so the turn ends on the function call alone (a genuine pause in
 * a resumable app). Either way the device's real result is injected later as a `FunctionResponse`
 * to resume the agent.
 */
internal class ChangeDestinationTool(private val respondImmediately: Boolean = true) :
  BaseTool(
    name = CHANGE_DESTINATION_TOOL,
    description = "Change the active navigation destination on the driver's device.",
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
    val destination = args[DESTINATION_ARG] ?: "<unknown>"
    println("   [backend] dispatching client action to the app: $name(destination=$destination)")
    return if (respondImmediately) mapOf("status" to "dispatched_to_client") else Unit
  }
}

/**
 * A deterministic scripted [Model] so the demos run without an API key: the first call requests the
 * [ChangeDestinationTool], and every later call returns a plain-text confirmation. Swap in a real
 * `Gemini(name = ...)` model (with an API key) to drive the same flow with an LLM.
 */
internal class ScriptedNavModel : Model {
  override val name: String = "scripted-nav-model"

  /**
   * Number of times the model has been invoked, to show the resumable-vs-not call-count contrast.
   */
  var invocations: Int = 0
    private set

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    val response =
      if (invocations++ == 0) {
        LlmResponse(
          content =
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(
                    functionCall =
                      FunctionCall(
                        name = CHANGE_DESTINATION_TOOL,
                        args = mapOf(DESTINATION_ARG to REQUESTED_DESTINATION),
                        id = "client-call-1",
                      )
                  )
                ),
            )
        )
      } else {
        LlmResponse(content = Content.fromText(Role.MODEL, "Your destination has been updated."))
      }
    return flowOf(response)
  }
}

/** The device's real result for the change-destination action, injected on resume. */
internal fun deviceResult(pausedCall: FunctionCall): Content =
  Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              name = pausedCall.name,
              id = pausedCall.id,
              response = mapOf("status" to "applied", "eta_minutes" to 12),
            )
        )
      ),
  )

/** Returns the pending long-running [FunctionCall] these events paused on, or null if none. */
internal fun List<Event>.pausedLongRunningCall(): FunctionCall? = firstNotNullOfOrNull { event ->
  event.functionCalls().firstOrNull { it.id != null && it.id in event.longRunningToolIds }
}

/** Prints a one-line summary of each event under [label]. */
internal fun printEvents(label: String, events: List<Event>) {
  println("-- $label (${events.size} event(s)) --")
  for (event in events) {
    println("   ${describeEvent(event)}")
  }
}

private fun describeEvent(event: Event): String {
  val calls = event.functionCalls()
  val responses = event.functionResponses()
  val text = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
  val detail =
    when {
      calls.isNotEmpty() ->
        "call " +
          calls.joinToString { "${it.name}(${it.args})" } +
          if (event.longRunningToolIds.isNotEmpty()) " [long-running]" else ""
      responses.isNotEmpty() ->
        "response " + responses.joinToString { "${it.name} -> ${it.response}" }
      text.isNotEmpty() -> "text \"$text\""
      else -> "(no content)"
    }
  val end = if (event.actions.endOfAgent) " {endOfAgent}" else ""
  return "${event.author}: $detail$end"
}
