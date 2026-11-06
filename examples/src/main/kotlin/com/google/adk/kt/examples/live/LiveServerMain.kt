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
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer

/**
 * Serves one live agent over the ADK web API, so a browser can hold a bidirectional conversation
 * with it.
 *
 * It hosts the bundled dev UI at `/dev-ui` and the live websocket at `/run_live`, keeping sessions
 * and artifacts in memory. Talk to it through the dev UI's microphone button: typed text goes to
 * `/run_sse`, which makes a non-live model call that a live-only model rejects.
 */
private class LiveServerCommand : CliktCommand(name = "live-server") {

  override fun help(context: Context): String =
    "Serves one live agent and the bundled dev UI on loopback, with no authentication."

  private val port: Int by
    option("--port", help = "Port to listen on.").int().default(AdkServerConfig.DEFAULT_PORT)

  override fun run() {
    val appName = "room_assistant"
    val agent =
      LlmAgent(
        name = appName,
        // Live models are served from a region, not the global endpoint other examples use.
        model =
          Gemini(
            name = "gemini-3.8-live",
            vertexCredentials = VertexCredentials(location = "us-central1"),
          ),
        instruction =
          Instruction(
            "You are a friendly assistant in a live conversation. Keep replies short. " +
              "Call getRoomStatus whenever the user asks about a meeting room, since you cannot " +
              "know room availability otherwise. Use Google Search for anything about current " +
              "events or recent facts, and say briefly where the answer came from."
          ),
        // getRoomStatus runs here; Google Search is a built-in the model runs on its side.
        tools = RoomTools().generatedTools() + GoogleSearchTool(),
      )

    println("Serving $appName at http://localhost:$port/dev-ui")
    AdkDevServer(AdkServerConfig.inMemory(agent, port)).start(wait = true)
  }
}

fun main(args: Array<String>) = LiveServerCommand().main(args)

/** The dev-UI server's tool, for a question the model cannot answer on its own. */
class RoomTools {

  /** Reports the status of a meeting room. */
  @Tool
  fun getRoomStatus(@Param("The room to look up, e.g. 'Zurich' or 'Krakow'") room: String): String =
    when (room.lowercase()) {
      "zurich",
      "zürich" -> "Zurich is free until 3pm and seats 8."
      "krakow",
      "kraków" -> "Krakow is booked until 5pm."
      else -> "There is no room called '$room'."
    }
}
