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
import com.google.adk.kt.apps.App;
import com.google.adk.kt.artifacts.InMemoryArtifactService;
import com.google.adk.kt.examples.hello.HelloAgentJava;
import com.google.adk.kt.examples.tools.AgentToolDemoAgentJava;
import com.google.adk.kt.examples.tools.GoogleSearchExampleJava;
import com.google.adk.kt.examples.transfer.AgentTransferDemoAgentJava;
import com.google.adk.kt.examples.workflow.AgentInWorkflowJava;
import com.google.adk.kt.examples.workflow.ClassifierNodeWorkflowJava;
import com.google.adk.kt.examples.workflow.DynamicFanOutFanInWorkflowJava;
import com.google.adk.kt.examples.workflow.DynamicNodesWorkflowJava;
import com.google.adk.kt.examples.workflow.FanOutFanInWorkflowJava;
import com.google.adk.kt.examples.workflow.LoopSelfWorkflowJava;
import com.google.adk.kt.examples.workflow.LoopWorkflowJava;
import com.google.adk.kt.examples.workflow.MessageWorkflowJava;
import com.google.adk.kt.examples.workflow.MultiTriggersWorkflowJava;
import com.google.adk.kt.examples.workflow.NestedWorkflowJava;
import com.google.adk.kt.examples.workflow.NodeOutputWorkflowJava;
import com.google.adk.kt.examples.workflow.ParallelWorkerWorkflowJava;
import com.google.adk.kt.examples.workflow.RequestInputAdvancedWorkflowJava;
import com.google.adk.kt.examples.workflow.RequestInputRerunWorkflowJava;
import com.google.adk.kt.examples.workflow.RequestInputWorkflowJava;
import com.google.adk.kt.examples.workflow.RetryWorkflowJava;
import com.google.adk.kt.examples.workflow.RouteWorkflowJava;
import com.google.adk.kt.examples.workflow.SequenceWorkflowJava;
import com.google.adk.kt.examples.workflow.StateWorkflowJava;
import com.google.adk.kt.examples.workflow.UseAsOutputWorkflowJava;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.sessions.InMemorySessionService;
import com.google.adk.kt.webserver.AdkApiServer;
import com.google.adk.kt.webserver.AdkServerConfig;
import com.google.adk.kt.webserver.ExperimentalAppInfoFeature;
import com.google.adk.kt.webserver.dev.AdkDevServer;
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader;
import com.google.adk.kt.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves several example agents and the ports of adk-python's workflow samples over HTTP, so the
 * Development UI can drive them.
 *
 * <p>The endpoints have no authentication, so this binds loopback and is meant for a local run.
 * Pass {@code --port} to change the port, {@code --dev} to also serve the Development UI (an {@link
 * AdkDevServer} instead of a plain {@link AdkApiServer}), and {@code --include-app-info} to mount
 * the endpoint reporting each agent's instruction and tools.
 */
public final class ExampleServerJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  @ExperimentalAppInfoFeature
  public static void main(String[] args) {
    int port = AdkServerConfig.DEFAULT_PORT;
    boolean dev = false;
    boolean includeAppInfo = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--dev" -> dev = true;
        case "--include-app-info" -> includeAppInfo = true;
        case "--port" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--port requires a value.");
          }
          port = Integer.parseInt(args[++i]);
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    List<App> apps = new ArrayList<>();
    apps.add(appOf(HelloAgentJava.rootAgent));
    apps.add(appOf(AgentToolDemoAgentJava.rootAgent));
    apps.add(appOf(GoogleSearchExampleJava.rootAgent));
    apps.add(appOf(AgentTransferDemoAgentJava.rootAgent));
    apps.addAll(
        workflowApps(
            new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build())));

    AdkServerConfig config =
        AdkServerConfig.builder()
            .appLoader(new InMemoryAppLoader(apps.toArray(new App[0])))
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .port(port)
            .includeAppInfo(includeAppInfo)
            .build();

    AdkApiServer server = dev ? new AdkDevServer(config) : new AdkApiServer(config);
    server.start(/* wait= */ true);
  }

  private ExampleServerJava() {}

  /** Serves {@code agent} as an app named after it. */
  private static App appOf(BaseAgent agent) {
    return App.builder().appName(agent.getName()).rootAgent(agent).build();
  }

  /**
   * The workflow ports as apps named after adk-python's sample directories, using {@code model}.
   */
  private static List<App> workflowApps(Model model) {
    return List.of(
        workflowApp("agent_in_workflow", AgentInWorkflowJava.create(model)),
        workflowApp("classifier_node", ClassifierNodeWorkflowJava.create(model)),
        workflowApp("dynamic_fan_out_fan_in", DynamicFanOutFanInWorkflowJava.create(model)),
        workflowApp("dynamic_nodes", DynamicNodesWorkflowJava.create(model)),
        workflowApp("fan_out_fan_in", FanOutFanInWorkflowJava.create()),
        workflowApp("loop", LoopWorkflowJava.create(model)),
        workflowApp("loop_self", LoopSelfWorkflowJava.create()),
        workflowApp("message", MessageWorkflowJava.create()),
        workflowApp("multi_triggers", MultiTriggersWorkflowJava.create()),
        workflowApp("nested_workflow", NestedWorkflowJava.create(model)),
        workflowApp("node_output", NodeOutputWorkflowJava.create(model)),
        workflowApp("parallel_worker", ParallelWorkerWorkflowJava.create(model)),
        workflowApp("request_input", RequestInputWorkflowJava.create(model)),
        workflowApp("request_input_advanced", RequestInputAdvancedWorkflowJava.create(model)),
        workflowApp("request_input_rerun", RequestInputRerunWorkflowJava.create(model)),
        workflowApp("retry", RetryWorkflowJava.create()),
        workflowApp("route", RouteWorkflowJava.create(model)),
        workflowApp("sequence", SequenceWorkflowJava.create(model)),
        workflowApp("state", StateWorkflowJava.create()),
        workflowApp("use_as_output", UseAsOutputWorkflowJava.create(model)));
  }

  private static App workflowApp(String appName, Workflow workflow) {
    return App.builder().appName(appName).rootNode(workflow).build();
  }
}
