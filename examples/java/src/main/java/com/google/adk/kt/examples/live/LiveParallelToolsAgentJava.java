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

import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.webserver.AdkServerConfig;
import com.google.adk.kt.webserver.dev.AdkDevServer;
import java.util.Map;

/**
 * A live home helper with two tools, so a request such as "turn on the lights and the TV" makes the
 * model call both in one turn.
 *
 * <p>{@link #main} serves it over the ADK web API, so a browser can talk to it through the bundled
 * dev UI's microphone button: typed text goes to {@code /run_sse}, which a live-only model rejects.
 */
public final class LiveParallelToolsAgentJava {

  /** Builds the agent on {@code model}. */
  public static LlmAgent create(Model model) {
    HomeDevices devices = new HomeDevices();
    return LlmAgent.builder()
        .name("Home_helper")
        .model(model)
        .instruction("Be polite and answer all user's questions.")
        .tools(
            ReflectiveTools.fromMethod(devices, "turnOnLights"),
            ReflectiveTools.fromMethod(devices, "turnOnTv"))
        .build();
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

    // Live models are served from a region, not the global endpoint other examples use.
    Model model =
        new Gemini("gemini-3.8-live", VertexCredentials.builder().location("us-central1").build());
    App app = App.builder().appName("parallel_tools_agent").rootAgent(create(model)).build();
    System.out.println("Serving " + app.getAppName() + " at http://localhost:" + port + "/dev-ui");
    new AdkDevServer(AdkServerConfig.inMemory(app, port)).start(/* wait= */ true);
  }

  /**
   * The home devices the agent can switch on. {@link ReflectiveTools} builds their tools, as this
   * module has no KSP.
   */
  static final class HomeDevices {

    @Tool(name = "turn_on_lights", description = "Turns on the lights.")
    public Map<String, String> turnOnLights() {
      System.out.println("turn_on_lights");
      return Map.of("status", "OK");
    }

    @Tool(name = "turn_on_tv", description = "Turns on the TV.")
    public Map<String, String> turnOnTv() {
      System.out.println("turn_on_tv");
      return Map.of("status", "OK");
    }
  }

  private LiveParallelToolsAgentJava() {}
}
