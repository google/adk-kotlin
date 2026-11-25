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
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.Fail
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.capturingLogs
import com.google.adk.kt.testing.connectionDrop
import com.google.adk.kt.testing.handleUpdate
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.genai.kotlin.GenAiApiException
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class LlmAgentLoggingTest {

  @Test
  fun runLive_idleReconnectsBetweenRefusals_logAndWaitByTheCountNearerItsLimit() = runTest {
    // Arrange: connects 1, 2, 4, 7, 8 and 10 open and drop with no turn; the rest are refused.
    val opens = setOf(1, 2, 4, 7, 8, 10)
    val opener =
      DummyLiveModel.perConnection(
        listOf(listOf(Respond(handleUpdate("h-1")), Fail(connectionDrop()))),
        afterScripts = listOf(Fail(connectionDrop())),
      )
    val connectTimes = mutableListOf<Long>()
    val model =
      object : Model {
        override val name = "refuses-some-connects"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {}

        override suspend fun connect(request: LlmRequest): LiveConnection {
          connectTimes += testScheduler.currentTime
          if (connectTimes.size !in opens) {
            throw IllegalStateException("The live session failed to open.", connectionDrop())
          }
          return opener.connect(request)
        }
      }
    val runner = InMemoryRunner(LlmAgent(name = "live_agent", model = model))

    // Act
    val (failure, records) =
      capturingLogs(LlmAgentTurn::class.java.name, Level.INFO) {
        assertFailsWith<GenAiApiException> { runner.runLive("u", "s", LiveRequestQueue()).toList() }
      }

    // Assert: connect 10 is the fifth idle reconnect, so its drop ends the run.
    assertEquals(1006, failure.code)
    assertEquals(10, connectTimes.size)
    // A refusal after an idle reconnect counts attempts again rather than repeat an idle number.
    val counts =
      listOf(
        "attempt 1",
        "idle reconnect 2",
        "attempt 2",
        "idle reconnect 3",
        "attempt 2",
        "attempt 3",
        "idle reconnect 4",
        "idle reconnect 5",
        "attempt 2",
      )
    assertEquals(
      counts.map { "Reconnecting the live session ($it of 5). Close code 1006." },
      records.map { it.message.orEmpty() }.filter { it.startsWith("Reconnecting") },
    )
    // Each wait stays within the ceiling its count sets: 250 ms, doubled for each count past 1.
    val ceilings = listOf(250L, 500L, 500L, 1000L, 500L, 1000L, 2000L, 4000L, 500L)
    val waits = connectTimes.zipWithNext { before, after -> after - before }
    assertTrue(
      waits.zip(ceilings).all { (wait, ceiling) -> wait <= ceiling },
      "waits $waits above $ceilings",
    )
  }

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
}
