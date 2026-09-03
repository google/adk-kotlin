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
import com.google.adk.kt.interop.BaseFutureTool;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.FunctionDeclaration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Example "Space Commander" agent demonstrating the Human-in-the-Loop (HITL) workflow: a high-risk
 * tool pauses for confirmation before it runs.
 *
 * <p>{@code scanPlanet} is safe and runs immediately; {@code initiateWarpJump} requires
 * confirmation. Because {@code requireConfirmation} on the Kotlin {@code @Tool} annotation is a
 * KSP-generated feature, this hand-written {@link BaseFutureTool} replicates the protocol itself:
 * the first call has no {@link ToolConfirmation}, so it requests one via {@link
 * ToolContext#requestConfirmation} and returns a placeholder; once the caller injects a confirmed
 * {@code ToolConfirmation}, the same call runs for real. Exercise it with {@code ReplRunner}, which
 * prompts the operator for the decision.
 *
 * <p>{@code scanPlanet} is built from an annotated method by {@link ReflectiveTools}; {@code
 * initiateWarpJump} stays a hand-written {@link BaseFutureTool} because the confirmation protocol
 * has no reflective shorthand.
 */
public final class HitlDemoAgentJava {

  /** The safe, no-confirmation tool, built from an annotated method by {@link ReflectiveTools}. */
  static final class ScanTools {
    @Tool(name = "scanPlanet", description = "Scans a nearby planet for life signs and resources.")
    public String scanPlanet() {
      System.out.println(
          ">>> [SYSTEM]: Scanning planet... Life signs detected: Minimal. Resources: Dilithium"
              + " present.");
      return "Planet contains Dilithium. Safe to approach.";
    }
  }

  /**
   * Initiates an FTL warp jump. High-risk, so it pauses for confirmation before running.
   *
   * <p>Hand-written as a {@link BaseFutureTool} because {@link ReflectiveTools} (the only
   * {@code @Tool} path in this javac module) refuses {@code requireConfirmation} tools, and the KSP
   * {@code @Tool} path that builds the confirmation protocol needs the Kotlin compiler.
   */
  private static final class InitiateWarpJumpTool extends BaseFutureTool {
    InitiateWarpJumpTool() {
      super("initiateWarpJump", "Initiates an FTL warp jump to the specified sector.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("initiateWarpJump")
          .description("Initiates an FTL warp jump to the specified sector.")
          .build();
    }

    @Override
    public CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      ToolConfirmation confirmation = context.getToolConfirmation();
      if (confirmation == null || !confirmation.getConfirmed()) {
        // Pause the invocation until the caller supplies a confirmed ToolConfirmation.
        context.requestConfirmation("Confirm FTL warp jump to the specified sector?", null);
        return CompletableFuture.completedFuture(
            Map.of(
                "status", "awaiting_confirmation", "message", "Warp jump requires confirmation."));
      }
      System.out.println(">>> [SYSTEM]: WARP DRIVE ENGAGED. Jumping to sector...");
      return CompletableFuture.completedFuture("Warp jump successful. Arrived at destination.");
    }
  }

  // Reflection is costly, so the tool is built once; prefer the KSP @Tool path when the Kotlin
  // compiler is available (see
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java).
  private static final BaseTool SCAN_PLANET =
      ReflectiveTools.fromMethod(new ScanTools(), "scanPlanet");

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("ship_computer")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are the AI computer of an interstellar exploration vessel.
              You assist the captain by scanning planets.
              If the captain orders a jump, you must use the initiateWarpJump tool.\
              """)
          .tools(SCAN_PLANET, new InitiateWarpJumpTool())
          .build();

  private HitlDemoAgentJava() {}
}
