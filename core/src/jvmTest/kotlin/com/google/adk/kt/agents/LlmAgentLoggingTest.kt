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

package com.google.adk.kt.agents

import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.capturingLogs
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

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
}
