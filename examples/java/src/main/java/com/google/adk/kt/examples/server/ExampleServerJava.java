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

package com.google.adk.kt.examples.server;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.artifacts.InMemoryArtifactService;
import com.google.adk.kt.examples.hello.HelloAgentJava;
import com.google.adk.kt.examples.tools.AgentToolDemoAgentJava;
import com.google.adk.kt.examples.tools.GoogleSearchExampleJava;
import com.google.adk.kt.examples.transfer.AgentTransferDemoAgentJava;
import com.google.adk.kt.sessions.InMemorySessionService;
import com.google.adk.kt.webserver.AdkApiServer;
import com.google.adk.kt.webserver.AdkServerConfig;
import com.google.adk.kt.webserver.dev.AdkDevServer;
import com.google.adk.kt.webserver.loaders.AgentLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves several example agents over HTTP, so the Development UI can drive them.
 *
 * <p>The endpoints have no authentication, so this binds loopback and is meant for a local run.
 * Pass {@code --port} to change the port and {@code --dev} to also serve the Development UI (an
 * {@link AdkDevServer} instead of a plain {@link AdkApiServer}).
 */
public final class ExampleServerJava {

  /** The example agents served, keyed by the name each one gives itself. */
  private static Map<String, BaseAgent> agentsByName() {
    Map<String, BaseAgent> byName = new LinkedHashMap<>();
    for (BaseAgent agent :
        List.of(
            HelloAgentJava.rootAgent,
            AgentToolDemoAgentJava.rootAgent,
            GoogleSearchExampleJava.rootAgent,
            AgentTransferDemoAgentJava.rootAgent)) {
      byName.put(agent.getName(), agent);
    }
    return byName;
  }

  /**
   * An {@link AgentLoader} over the several example agents this server exposes; a single-agent
   * loader such as {@code SingleAgentLoader} cannot serve them all.
   */
  private static final class ExampleAgentLoader implements AgentLoader {
    private final Map<String, BaseAgent> agents;

    ExampleAgentLoader(Map<String, BaseAgent> agents) {
      this.agents = agents;
    }

    @Override
    public List<String> listAgents() {
      List<String> names = new ArrayList<>(agents.keySet());
      names.sort(null);
      return names;
    }

    @Override
    public BaseAgent loadAgent(String agentName) {
      return agents.get(agentName);
    }
  }

  public static void main(String[] args) {
    int port = AdkServerConfig.DEFAULT_PORT;
    boolean dev = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--dev" -> dev = true;
        case "--port" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--port requires a value.");
          }
          port = Integer.parseInt(args[++i]);
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    AdkServerConfig config =
        AdkServerConfig.builder()
            .agentLoader(new ExampleAgentLoader(agentsByName()))
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .port(port)
            .build();

    AdkApiServer server = dev ? new AdkDevServer(config) : new AdkApiServer(config);
    server.start(/* wait= */ true);
  }

  private ExampleServerJava() {}
}
