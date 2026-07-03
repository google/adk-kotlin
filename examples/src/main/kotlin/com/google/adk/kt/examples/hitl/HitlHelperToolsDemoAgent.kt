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

package com.google.adk.kt.examples.hitl

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.tools.GetUserChoiceTool
import com.google.adk.kt.tools.RequestInputTool

/**
 * Example agent using the built-in HITL helper tools [RequestInputTool] and [GetUserChoiceTool].
 *
 * Unlike [HitlDemoAgent] (which gates a `@Tool` behind approve/reject confirmation), these let the
 * agent ask the user for free-form input or to pick from options. Both pause the invocation until
 * the caller injects a matching `FunctionResponse`, mirroring Python ADK.
 */
object HitlHelperToolsDemoAgent {
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "trip_planner",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are a travel-planning assistant.
          When you need details you don't have, call adk_request_input to ask the user a question.
          When the user should pick between concrete alternatives, call get_user_choice with the
          list of options. Wait for the user's response before continuing.
          """
            .trimIndent()
        ),
      tools = listOf(RequestInputTool(), GetUserChoiceTool()),
    )
}
