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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.capturingLogs
import com.google.adk.kt.testing.goAway
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LlmAgentLoggingTest {

  @Test
  fun runAsync_outputSchemaMismatch_logsWithoutModelOutput() = runBlocking {
    // Arrange: a schema error on this valid JSON would quote the value.
    val secret = "private-model-text"
    val rawOutput = """{"n": "$secret"}"""
    val schema = Schema(type = Type.OBJECT, properties = mapOf("n" to Schema(type = Type.INTEGER)))
    val model =
      DummyModel.createSequential(
        "gemini-2.0-flash",
        listOf(LlmResponse(content = modelMessage(rawOutput))),
      )
    val agent =
      LlmAgent(name = "test-agent", model = model, outputSchema = schema, outputKey = "result")
    val session = InMemorySessionService().createSession(SessionKey("app", "user", "session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    // Act
    val (events, records) =
      capturingLogs(LlmAgent::class.java.name, Level.SEVERE) { agent.runAsync(context).toList() }

    // Assert: the raw text is still saved and the mismatch still logged, without the model's text.
    assertEquals(rawOutput, events.single().actions.stateDelta["result"])
    assertTrue(records.isNotEmpty())
    for (record in records) {
      assertFalse(secret in record.message.orEmpty())
      assertFalse(
        generateSequence(record.thrown) { it.cause }.any { secret in it.message.orEmpty() }
      )
    }
  }

  @Test
  fun runLive_outputSchema_warnsThatLiveModeDoesNotSupportIt() {
    val agent =
      LlmAgent(name = "reporter", model = DummyLiveModel(emptyList()), outputSchema = REPORT)

    assertEquals(listOf(OUTPUT_SCHEMA_WARNING), liveRunWarnings(agent))
  }

  @Test
  fun runLive_outputSchemaAcrossARestartedSession_warnsOnce() {
    // A go-away without a resumption handle restarts the session, which prepares its request again.
    val model =
      DummyLiveModel.perConnection(listOf(listOf(Respond(goAway())), listOf(turnCompleteStep())))
    val agent = LlmAgent(name = "reporter", model = model, outputSchema = REPORT)

    val warnings = liveRunWarnings(agent, LiveRequestQueue())

    assertEquals(2, model.connections.size)
    assertEquals(listOf(OUTPUT_SCHEMA_WARNING), warnings)
  }

  @OptIn(ExperimentalWorkflowApi::class)
  @Test
  fun runLive_taskNodeWithOutputSchema_doesNotWarn() {
    // A task agent reports its structured output through finish_task, which works live too.
    val finish = modelFunctionCallResponse("finish_task", mapOf("answer" to "42"))
    val model = DummyLiveModel(listOf(Respond(finish)))
    val writer =
      LlmAgent(name = "writer", model = model, outputSchema = REPORT, mode = AgentMode.TASK)
    val starter =
      node<Any?, Any?>("starter", rerunOnResume = true) { context, _ -> context.runNode(writer) }
    val app =
      App(appName = "app", rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, starter))))

    val (events, records) =
      capturingLogs(LlmAgent::class.java.name, Level.WARNING) {
        runBlocking {
          withTimeout(5.seconds) {
            InMemoryRunner(app = app).runLive("u", "s", LiveRequestQueue()).toList()
          }
        }
      }

    assertEquals(1, model.connections.size)
    assertTrue(events.any { it.output == mapOf("answer" to "42") })
    assertEquals(emptyList(), records.map { it.message })
  }

  @Test
  fun runLive_noOutputSchema_doesNotWarn() {
    val agent = LlmAgent(name = "replier", model = DummyLiveModel(emptyList()))

    assertEquals(emptyList(), liveRunWarnings(agent))
  }

  @Test
  fun runLive_requestPreparationEndsInvocation_doesNotWarn() {
    val stopper =
      object : BaseTool(name = "stopper", description = "ends the invocation") {
        override fun declaration(): FunctionDeclaration? = null

        override suspend fun processLlmRequest(
          toolContext: ToolContext,
          llmRequest: LlmRequest,
        ): LlmRequest {
          toolContext.endInvocation()
          return llmRequest
        }

        override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
          emptyMap<String, Any?>()
      }
    val model = DummyLiveModel(emptyList())
    val agent =
      LlmAgent(name = "reporter", model = model, tools = listOf(stopper), outputSchema = REPORT)

    val warnings = liveRunWarnings(agent, LiveRequestQueue())

    assertEquals(0, model.connections.size)
    assertEquals(emptyList(), warnings)
  }

  /** The warnings [agent] logs on a live run that hangs up on [queue] once a turn completes. */
  private fun liveRunWarnings(
    agent: LlmAgent,
    queue: LiveRequestQueue = LiveRequestQueue().apply { close() },
  ): List<String?> {
    val (_, records) =
      capturingLogs(LlmAgent::class.java.name, Level.WARNING) {
        runBlocking {
          withTimeout(5.seconds) {
            InMemoryRunner(agent = agent)
              .runLive("u", "s", queue)
              .onEach { if (it.turnComplete) queue.close() }
              .toList()
          }
        }
      }
    return records.map { it.message }
  }

  private companion object {
    val REPORT =
      Schema(type = Type.OBJECT, properties = mapOf("answer" to Schema(type = Type.STRING)))

    const val OUTPUT_SCHEMA_WARNING =
      "Agent reporter sets outputSchema, which is not supported in live mode. To capture " +
        "structured data during a live session, give the agent a tool that writes it to session " +
        "state."
  }
}
