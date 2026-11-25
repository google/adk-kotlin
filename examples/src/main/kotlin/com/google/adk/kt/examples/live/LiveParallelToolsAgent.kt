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

package com.google.adk.kt.examples.live

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer

/**
 * A live home helper with two tools, so a request such as "turn on the lights and the TV" makes the
 * model call both in one turn.
 */
object LiveParallelToolsAgent {

  /** Builds the agent on [model]. */
  fun create(model: Model): LlmAgent =
    LlmAgent(
      name = "Home_helper",
      model = model,
      instruction = Instruction("Be polite and answer all user's questions."),
      tools = HomeDevices().generatedTools(),
    )
}

/**
 * Serves the agent over the ADK web API, so a browser can talk to it through the bundled dev UI's
 * microphone button: typed text goes to `/run_sse`, which a live-only model rejects.
 */
private class LiveParallelToolsCommand : CliktCommand(name = "live-parallel-tools") {

  override fun help(context: Context): String =
    "Serves the live parallel tools agent and the bundled dev UI on loopback, with no" +
      " authentication."

  private val port: Int by
    option("--port", help = "Port to listen on.").int().default(AdkServerConfig.DEFAULT_PORT)

  override fun run() {
    // Live models are served from a region, not the global endpoint other examples use.
    val model =
      Gemini(
        name = "gemini-3.8-live",
        vertexCredentials = VertexCredentials(location = "us-central1"),
      )
    val app =
      App(appName = "parallel_tools_agent", rootAgent = LiveParallelToolsAgent.create(model))
    println("Serving ${app.appName} at http://localhost:$port/dev-ui")
    AdkDevServer(AdkServerConfig.inMemory(app, port)).start(wait = true)
  }
}

fun main(args: Array<String>) {
  LiveParallelToolsCommand().main(args)
}

/** The home devices the agent can switch on. */
class HomeDevices {

  /** Turns on the lights. */
  @Tool(name = "turn_on_lights")
  fun turnOnLights(): Map<String, String> {
    println("turn_on_lights")
    return mapOf("status" to "OK")
  }

  /** Turns on the TV. */
  @Tool(name = "turn_on_tv")
  fun turnOnTv(): Map<String, String> {
    println("turn_on_tv")
    return mapOf("status" to "OK")
  }
}
