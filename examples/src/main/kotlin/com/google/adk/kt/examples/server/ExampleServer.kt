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
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.examples.hello.HelloAgent
import com.google.adk.kt.examples.tools.AgentToolDemoAgent
import com.google.adk.kt.examples.tools.FunctionToolDemoAgent
import com.google.adk.kt.examples.tools.GoogleSearchExample
import com.google.adk.kt.examples.transfer.AgentTransferDemoAgent
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.webserver.AdkApiServer
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.loaders.AgentLoader

/**
 * A sample of the example agents, by the name each one gives itself.
 *
 * Built on first use: constructing a Gemini agent needs an API key, and `--help` must not.
 */
private val agents: Map<String, BaseAgent> by lazy {
  listOf(
      HelloAgent.rootAgent,
      AgentToolDemoAgent.rootAgent,
      FunctionToolDemoAgent.rootAgent,
      GoogleSearchExample.rootAgent,
      AgentTransferDemoAgent.rootAgent,
    )
    .associateBy { it.name }
}

private object ExampleAgentLoader : AgentLoader {
  override fun listAgents(): List<String> = agents.keys.sorted()

  override fun loadAgent(agentName: String): BaseAgent? = agents[agentName]
}

/**
 * Serves five of the example agents over HTTP, so the Development UI can drive them.
 *
 * The endpoints have no authentication, so this binds loopback and is meant for a local run;
 * reaching it from anywhere else needs an authenticating layer in front.
 */
private class ExampleServerCommand : CliktCommand(name = "example-server") {

  override fun help(context: Context): String =
    "Serves five of the example agents on loopback, with no authentication. Send an agent " +
      "name to /run, or pass --dev and pick one in the Development UI."

  private val port: Int by
    option("--port", help = "Port to listen on.").int().default(AdkServerConfig.DEFAULT_PORT)

  private val dev: Boolean by
    option("--dev", help = "Also serve the Development UI and the endpoints it drives.").flag()

  override fun run() {
    val config =
      AdkServerConfig(
        agentLoader = ExampleAgentLoader,
        sessionService = InMemorySessionService(),
        artifactService = InMemoryArtifactService(),
        port = port,
      )

    val server = if (dev) AdkDevServer(config) else AdkApiServer(config)
    server.start(wait = true)
  }
}

fun main(args: Array<String>) = ExampleServerCommand().main(args)
