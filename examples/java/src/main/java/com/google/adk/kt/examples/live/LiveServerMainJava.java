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

package com.google.adk.kt.examples.live;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.GoogleSearchTool;
import com.google.adk.kt.webserver.AdkServerConfig;
import com.google.adk.kt.webserver.dev.AdkDevServer;
import java.util.Locale;

/**
 * Serves one live agent over the ADK web API, so a browser can hold a bidirectional conversation
 * with it.
 *
 * <p>It hosts the bundled dev UI at {@code /dev-ui} and the live websocket at {@code /run_live},
 * keeping sessions and artifacts in memory. Talk to it through the dev UI's microphone button:
 * typed text goes to {@code /run_sse}, which makes a non-live model call that a live-only model
 * rejects.
 */
public final class LiveServerMainJava {

  /**
   * The dev-UI server's tool, for a question the model cannot answer on its own. {@link
   * ReflectiveTools} builds it, as this module has no KSP.
   */
  static final class RoomTools {

    @Tool(description = "Reports the status of a meeting room.")
    public String getRoomStatus(
        @Param(name = "room", description = "The room to look up, e.g. 'Zurich' or 'Krakow'")
            String room) {
      return switch (room.toLowerCase(Locale.ROOT)) {
        case "zurich", "zürich" -> "Zurich is free until 3pm and seats 8.";
        case "krakow", "kraków" -> "Krakow is booked until 5pm.";
        default -> "There is no room called '" + room + "'.";
      };
    }
  }

  public static void main(String[] args) {
    int port = AdkServerConfig.DEFAULT_PORT;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--port requires a value.");
          }
          port = Integer.parseInt(args[++i]);
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    String appName = "room_assistant";
    BaseAgent agent =
        LlmAgent.builder()
            .name(appName)
            // Live models are served from a region, not the global endpoint other examples use.
            .model(
                new Gemini(
                    "gemini-3.8-live", VertexCredentials.builder().location("us-central1").build()))
            .instruction(
                "You are a friendly assistant in a live conversation. Keep replies short. Call"
                    + " getRoomStatus whenever the user asks about a meeting room, since you cannot"
                    + " know room availability otherwise. Use Google Search for anything about"
                    + " current events or recent facts, and say briefly where the answer came"
                    + " from.")
            // getRoomStatus runs here; Google Search is a built-in the model runs on its side.
            .tools(
                ReflectiveTools.fromMethod(new RoomTools(), "getRoomStatus"),
                GoogleSearchTool.builder().build())
            .build();

    System.out.println("Serving " + appName + " at http://localhost:" + port + "/dev-ui");
    new AdkDevServer(AdkServerConfig.inMemory(agent, port)).start(/* wait= */ true);
  }

  private LiveServerMainJava() {}
}
