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

package com.google.adk.kt.examples.environment

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.tools.environment.EnvironmentToolset

/**
 * Example agent demonstrating the environment tools over a [LocalEnvironment].
 *
 * An [EnvironmentToolset] wraps a [LocalEnvironment] and exposes the `Execute`, `ReadFile`,
 * `EditFile`, and `WriteFile` tools, so the agent can run shell commands and manage files in a
 * scratch working directory. The toolset lazily creates a temporary workspace on first use and
 * removes it in [EnvironmentToolset.close]; this debug run (a
 * [com.google.adk.kt.runners.ReplRunner] that never calls `close`) leaves that directory for the OS
 * to reclaim.
 *
 * The toolset injects its own system instruction (workspace framing and tool-selection rules) on
 * each call, so the [Instruction] below only sets the agent's task framing.
 */
@OptIn(ExperimentalEnvironmentApi::class)
object LocalEnvironmentDemoAgent {
  /** The agent, equipped with an [EnvironmentToolset] backed by a local subprocess environment. */
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "local_environment_agent",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are a command-line assistant with a scratch workspace directory.
          Use your tools to run shell commands and to read, write, and edit files
          in that workspace. When the user asks for something, actually carry it
          out with the tools rather than describing it, then summarize what you
          did and show any relevant command output.
          """
            .trimIndent()
        ),
      toolsets = listOf(EnvironmentToolset(LocalEnvironment())),
    )
}
