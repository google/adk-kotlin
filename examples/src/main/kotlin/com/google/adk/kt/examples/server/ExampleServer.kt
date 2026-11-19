/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.examples.server

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.examples.hello.HelloAgent
import com.google.adk.kt.examples.tools.AgentToolDemoAgent
import com.google.adk.kt.examples.tools.FunctionToolDemoAgent
import com.google.adk.kt.examples.tools.GoogleSearchExample
import com.google.adk.kt.examples.transfer.AgentTransferDemoAgent
import com.google.adk.kt.examples.workflow.AgentInWorkflow
import com.google.adk.kt.examples.workflow.ClassifierNodeWorkflow
import com.google.adk.kt.examples.workflow.DynamicFanOutFanInWorkflow
import com.google.adk.kt.examples.workflow.DynamicNodesWorkflow
import com.google.adk.kt.examples.workflow.FanOutFanInWorkflow
import com.google.adk.kt.examples.workflow.LoopSelfWorkflow
import com.google.adk.kt.examples.workflow.LoopWorkflow
import com.google.adk.kt.examples.workflow.MessageWorkflow
import com.google.adk.kt.examples.workflow.MultiTriggersWorkflow
import com.google.adk.kt.examples.workflow.NestedWorkflow
import com.google.adk.kt.examples.workflow.NodeOutputWorkflow
import com.google.adk.kt.examples.workflow.ParallelWorkerWorkflow
import com.google.adk.kt.examples.workflow.RequestInputAdvancedWorkflow
import com.google.adk.kt.examples.workflow.RequestInputRerunWorkflow
import com.google.adk.kt.examples.workflow.RequestInputWorkflow
import com.google.adk.kt.examples.workflow.RetryWorkflow
import com.google.adk.kt.examples.workflow.RouteWorkflow
import com.google.adk.kt.examples.workflow.SequenceWorkflow
import com.google.adk.kt.examples.workflow.StateWorkflow
import com.google.adk.kt.examples.workflow.UseAsOutputWorkflow
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.webserver.AdkApiServer
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader

/**
 * Serves five of the example agents and the ports of adk-python's workflow samples over HTTP, so
 * the Development UI can drive them.
 *
 * The endpoints have no authentication, so this binds loopback and is meant for a local run;
 * reaching it from anywhere else needs an authenticating layer in front.
 */
private class ExampleServerCommand : CliktCommand(name = "example-server") {

  override fun help(context: Context): String =
    "Serves example agents and workflows on loopback, with no authentication. Send an app " +
      "name to /run, or pass --dev and pick one in the Development UI."

  private val port: Int by
    option("--port", help = "Port to listen on.").int().default(AdkServerConfig.DEFAULT_PORT)

  private val dev: Boolean by
    option("--dev", help = "Also serve the Development UI and the endpoints it drives.").flag()

  private val camelCase: Boolean by
    option(
        "--camel-case-enforced",
        help =
          "Emit enforced camelCase spelling in all responses. Off by default, " +
            "since the Development UI reads the older spelling on /version and the trace views.",
      )
      .flag()

  private val includeAppInfo: Boolean by
    option(
        "--include-app-info",
        help =
          "Mount /apps/{appName}/app-info, which reports every agent's instruction and tools. " +
            "Off by default, since a deployment has no reason to publish them.",
      )
      .flag()

  override fun run() {
    // Built here so --help needs no Google Cloud project: constructing a Gemini agent requires one.
    val config =
      AdkServerConfig(
        appLoader =
          InMemoryAppLoader(
            appOf(HelloAgent.rootAgent),
            appOf(AgentToolDemoAgent.rootAgent),
            appOf(FunctionToolDemoAgent.rootAgent),
            appOf(GoogleSearchExample.rootAgent),
            appOf(AgentTransferDemoAgent.rootAgent),
            *workflowApps(
              Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
            ),
          ),
        sessionService = InMemorySessionService(),
        artifactService = InMemoryArtifactService(),
        port = port,
        camelCaseEnforced = camelCase,
        includeAppInfo = includeAppInfo,
      )

    val server = if (dev) AdkDevServer(config) else AdkApiServer(config)
    server.start(wait = true)
  }
}

fun main(args: Array<String>) = ExampleServerCommand().main(args)

private const val MODEL_NAME = "gemini-3.8-flash"

/** Serves [agent] as an app named after it. */
private fun appOf(agent: BaseAgent): App = App(appName = agent.name, rootAgent = agent)

/** The workflow ports as apps named after adk-python's sample directories, using [model]. */
@OptIn(ExperimentalWorkflowApi::class)
private fun workflowApps(model: Model): Array<App> =
  arrayOf(
    App(appName = "agent_in_workflow", rootNode = AgentInWorkflow.create(model)),
    App(appName = "classifier_node", rootNode = ClassifierNodeWorkflow.create(model)),
    App(appName = "dynamic_fan_out_fan_in", rootNode = DynamicFanOutFanInWorkflow.create(model)),
    App(appName = "dynamic_nodes", rootNode = DynamicNodesWorkflow.create(model)),
    App(appName = "fan_out_fan_in", rootNode = FanOutFanInWorkflow.create()),
    App(appName = "loop", rootNode = LoopWorkflow.create(model)),
    App(appName = "loop_self", rootNode = LoopSelfWorkflow.create()),
    App(appName = "message", rootNode = MessageWorkflow.create()),
    App(appName = "multi_triggers", rootNode = MultiTriggersWorkflow.create()),
    App(appName = "nested_workflow", rootNode = NestedWorkflow.create(model)),
    App(appName = "node_output", rootNode = NodeOutputWorkflow.create(model)),
    App(appName = "parallel_worker", rootNode = ParallelWorkerWorkflow.create(model)),
    App(appName = "request_input", rootNode = RequestInputWorkflow.create(model)),
    App(appName = "request_input_advanced", rootNode = RequestInputAdvancedWorkflow.create(model)),
    App(appName = "request_input_rerun", rootNode = RequestInputRerunWorkflow.create(model)),
    App(appName = "retry", rootNode = RetryWorkflow.create()),
    App(appName = "route", rootNode = RouteWorkflow.create(model)),
    App(appName = "sequence", rootNode = SequenceWorkflow.create(model)),
    App(appName = "state", rootNode = StateWorkflow.create()),
    App(appName = "use_as_output", rootNode = UseAsOutputWorkflow.create(model)),
  )
