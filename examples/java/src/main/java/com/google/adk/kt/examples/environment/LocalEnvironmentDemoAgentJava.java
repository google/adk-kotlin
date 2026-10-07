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

package com.google.adk.kt.examples.environment;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.environment.LocalEnvironment;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.runners.ReplRunner;
import com.google.adk.kt.tools.environment.EnvironmentToolset;
import java.util.Set;

/**
 * Example agent that runs shell commands and edits files through an {@link EnvironmentToolset} over
 * a {@link LocalEnvironment}.
 *
 * <p>The environment creates a temporary workspace when first used, and {@link #main} has the
 * runner close the toolset on exit, which deletes it. The toolset appends its own tool-selection
 * rules to the system instruction, so the instruction below only frames the task.
 */
public final class LocalEnvironmentDemoAgentJava {

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("local_environment_agent")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are a command-line assistant with a scratch workspace directory.
              Use your tools to run shell commands and to read, write, and edit files
              in that workspace. When the user asks for something, actually carry it
              out with the tools rather than describing it, then summarize what you
              did and show any relevant command output.\
              """)
          .toolsets(
              new EnvironmentToolset(
                  // Commands inherit only these host variables, so API keys stay hidden.
                  LocalEnvironment.builder()
                      .inheritedEnvVarAllowlist(Set.of("PATH", "HOME", "LANG", "TMPDIR"))
                      .build()))
          .build();

  /** Runs the agent in a REPL; closing the runner closes the toolset, deleting the workspace. */
  public static void main(String[] args) {
    try (ReplRunner runner = new ReplRunner(rootAgent)) {
      runner.start();
    }
  }

  private LocalEnvironmentDemoAgentJava() {}
}
