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
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials

/**
 * Tools available to the [HitlDemoAgent]. The KSP `@Tool` processor generates a `FunctionTool`
 * subclass for each annotated method and a `generatedTools()` extension that returns the full list.
 */
class ShipComputerTools {

  /** Scans a nearby planet for life signs and resources. Safe; runs without confirmation. */
  @Tool
  fun scanPlanet(): String {
    println(
      ">>> 🪐 [SYSTEM]: Scanning planet... Life signs detected: Minimal. Resources: Dilithium present."
    )
    return "Planet contains Dilithium. Safe to approach."
  }

  /**
   * Initiates an FTL warp jump to the specified sector. High-risk, so [Tool.requireConfirmation] is
   * set so the framework pauses every invocation until the caller supplies a
   * [com.google.adk.kt.events.ToolConfirmation].
   */
  @Tool(requireConfirmation = true)
  fun initiateWarpJump(): String {
    println(">>> 🚀 [SYSTEM]: WARP DRIVE ENGAGED. Jumping to sector...")
    return "Warp jump successful. Arrived at destination."
  }
}

/**
 * Example Space Commander agent demonstrating the Human-in-the-Loop (HITL) workflow using the
 * Kotlin ADK.
 *
 * This example showcases how to declare a `@Tool`-annotated function with `requireConfirmation =
 * true` so that the framework pauses agent execution and requests human confirmation before
 * executing a potentially destructive or high-risk tool (here, `initiateWarpJump`).
 *
 * The agent is configured to use Gemini on Vertex AI for inference, behaving like a ship's computer
 * equipped with the tools defined in [ShipComputerTools].
 *
 * ## How it works
 * 1. The agent is configured with the tools generated from [ShipComputerTools]: `scanPlanet` (safe)
 *    and `initiateWarpJump` (risky, gated by `requireConfirmation = true`).
 * 2. When the LLM decides to jump, the generated wrapper for `initiateWarpJump` records a
 *    confirmation request via [com.google.adk.kt.tools.ToolContext.requestConfirmation] and returns
 *    a placeholder error response.
 * 3. The execution loop pauses, waiting for the user to provide a
 *    [com.google.adk.kt.events.ToolConfirmation].
 * 4. Once the confirmation is injected via a `FunctionResponse` for the synthetic
 *    `adk_request_confirmation` call, the flow resumes and executes the tool.
 *
 * The example can be exercised interactively with [com.google.adk.kt.runners.ReplRunner], which
 * prompts the operator for a yes/no decision whenever a tool gated by `requireConfirmation = true`
 * is about to run.
 */
object HitlDemoAgent {
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "ship_computer",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      instruction =
        Instruction(
          """
          You are the AI computer of an interstellar exploration vessel.
          You assist the captain by scanning planets.
          If the captain orders a jump, you must use the initiateWarpJump tool.
          """
            .trimIndent()
        ),
      // Note: We expect the KSP processor to generate this extension for ShipComputerTools.
      tools = ShipComputerTools().generatedTools(),
    )
}
