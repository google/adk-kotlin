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

package com.google.adk.kt.examples.hitl;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.events.ToolConfirmation;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.BaseTool;

/**
 * Example "Space Commander" agent demonstrating the Human-in-the-Loop (HITL) workflow: a high-risk
 * tool pauses for confirmation before it runs.
 *
 * <p>{@code scanPlanet} is safe and runs immediately; {@code initiateWarpJump} sets {@code
 * requireConfirmation}, so its first call requests a {@link ToolConfirmation} and returns a
 * placeholder error, while a call with a confirmed {@code ToolConfirmation} runs the tool. Exercise
 * it with {@code ReplRunner}, which prompts the operator for the decision.
 */
public final class HitlDemoAgentJava {

  /** The ship computer's tools, built from annotated methods by {@link ReflectiveTools}. */
  static final class ShipComputerTools {
    @Tool(name = "scanPlanet", description = "Scans a nearby planet for life signs and resources.")
    public String scanPlanet() {
      System.out.println(
          ">>> [SYSTEM]: Scanning planet... Life signs detected: Minimal. Resources: Dilithium"
              + " present.");
      return "Planet contains Dilithium. Safe to approach.";
    }

    @Tool(
        name = "initiateWarpJump",
        description = "Initiates an FTL warp jump to the specified sector.",
        requireConfirmation = true)
    public String initiateWarpJump() {
      System.out.println(">>> [SYSTEM]: WARP DRIVE ENGAGED. Jumping to sector...");
      return "Warp jump successful. Arrived at destination.";
    }
  }

  // Reflection is costly, so the tools are built once; prefer the KSP @Tool path when the Kotlin
  // compiler is available (see
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java).
  private static final ShipComputerTools TOOLS = new ShipComputerTools();
  private static final BaseTool SCAN_PLANET = ReflectiveTools.fromMethod(TOOLS, "scanPlanet");
  private static final BaseTool INITIATE_WARP_JUMP =
      ReflectiveTools.fromMethod(TOOLS, "initiateWarpJump");

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("ship_computer")
          .model(
              new Gemini(
                  "gemini-3.8-flash", VertexCredentials.builder().location("global").build()))
          .instruction(
              """
              You are the AI computer of an interstellar exploration vessel.
              You assist the captain by scanning planets.
              If the captain orders a jump, you must use the initiateWarpJump tool.\
              """)
          .tools(SCAN_PLANET, INITIATE_WARP_JUMP)
          .build();

  private HitlDemoAgentJava() {}
}
