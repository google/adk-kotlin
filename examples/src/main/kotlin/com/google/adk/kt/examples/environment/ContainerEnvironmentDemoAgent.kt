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
import com.google.adk.kt.environment.ContainerEnvironment
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.tools.environment.EnvironmentToolset

/**
 * Example agent demonstrating the environment tools over a [ContainerEnvironment].
 *
 * An [EnvironmentToolset] wraps a [ContainerEnvironment] and exposes the `Execute`, `ReadFile`,
 * `EditFile`, and `WriteFile` tools, so the agent can run shell commands and manage files inside a
 * Docker container. The toolset starts the container on first use (`docker run ... sleep infinity`)
 * and force-removes it in [EnvironmentToolset.close].
 *
 * Requirements to run:
 * - The `docker` CLI on `PATH` and a running Docker daemon: every operation shells out to `docker`
 *   (`run`, `exec`, `cp`, `rm`), so both the client binary and a reachable daemon are needed.
 * - Permission to talk to the daemon socket. The invoking user must be in the `docker` group, e.g.
 *   `sudo usermod -aG docker "$USER"` followed by a fresh login shell or `newgrp docker`. When the
 *   agent is launched through Blaze, run `blaze shutdown` afterward so the persistent build server
 *   restarts with the new group membership (or launch the run via `sg docker -c '...'`).
 * - Network access to pull `python:3.11-slim` on first use. If the image is already present
 *   locally, construct `ContainerEnvironment(image = ..., pullImage = false)` to skip the `docker
 *   pull`.
 *
 * Because this debug run uses a [com.google.adk.kt.runners.ReplRunner] that never calls
 * [EnvironmentToolset.close], the container is left running after the session ends; reclaim it with
 * `docker rm -f <id>` (locate it via `docker ps`).
 *
 * The toolset injects its own system instruction (environment identity, working directory, and
 * tool-selection rules) on each call, so the [Instruction] below only sets the agent's task
 * framing.
 */
@OptIn(ExperimentalEnvironmentApi::class)
object ContainerEnvironmentDemoAgent {
  /** The agent, equipped with an [EnvironmentToolset] backed by a Docker container environment. */
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "container_environment_agent",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are a command-line assistant with a scratch workspace directory
          inside a Docker container. Use your tools to run shell commands and to
          read, write, and edit files in that workspace. When the user asks for
          something, actually carry it out with the tools rather than describing
          it, then summarize what you did and show any relevant command output.
          """
            .trimIndent()
        ),
      toolsets = listOf(EnvironmentToolset(ContainerEnvironment(image = "python:3.11-slim"))),
    )
}
