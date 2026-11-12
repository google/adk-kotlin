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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.webserver.telemetry

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import java.net.HttpURLConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A run that calls no model shows its trace for the session on the dev server.
 *
 * Core resolves its tracer once per JVM, so this class needs a JVM of its own, which the
 * `endToEndTest` Gradle task gives it.
 */
@RunWith(JUnit4::class)
class SessionTraceEndToEndTest {

  @Test(timeout = 60_000)
  fun devServer_runWithoutModelCall_listsItsTraceForTheSession() {
    val port = freePort()
    val server = AdkDevServer(config(port))
    try {
      server.start()
      awaitHealthy(port)
      assertThat(postRun(port, APP)).isEqualTo(HttpURLConnection.HTTP_OK)

      val paths =
        listOf(
          "/debug/trace/session/$RUN_SESSION_ID",
          "/dev/apps/$APP/debug/trace/session/$RUN_SESSION_ID",
        )
      for (path in paths) {
        val spans =
          Json.parseToJsonElement(httpGet(port, path)).jsonArray.associateBy {
            it.jsonObject.getValue("name").jsonPrimitive.content
          }
        val invocation = spans.getValue("invocation").jsonObject
        val workflow = spans.getValue("invoke_workflow wf").jsonObject
        val node = spans.getValue("invoke_node greet").jsonObject
        assertThat(invocation.getValue("parent_span_id")).isEqualTo(JsonNull)
        assertThat(workflow.getValue("parent_span_id")).isEqualTo(invocation.getValue("span_id"))
        assertThat(node.getValue("parent_span_id")).isEqualTo(workflow.getValue("span_id"))
      }
    } finally {
      server.stop()
    }
  }

  private fun config(port: Int): AdkServerConfig {
    val sessions = InMemorySessionService()
    runBlocking {
      val unused = sessions.createSession(SessionKey(APP, RUN_USER_ID, RUN_SESSION_ID))
    }
    return AdkServerConfig(
      appLoader =
        InMemoryAppLoader(
          App(appName = APP, rootNode = Workflow("wf", edges = listOf(Edge(Start, Greet))))
        ),
      sessionService = sessions,
      artifactService = InMemoryArtifactService(),
      port = port,
      // The assertions read the snake_case span fields.
      camelCaseEnforced = false,
    )
  }

  /** A function node, so the run makes no model call. */
  private object Greet : Node {
    override val name = "greet"

    override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit("hello") }
  }

  private companion object {
    const val APP = "e2e-workflow"
  }
}
