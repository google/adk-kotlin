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
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Covers the output [LlmAgent] saves on the JVM, where Gson reads every JSON number as a double.
 */
class LlmAgentOutputJvmTest {

  @Test
  fun runAsync_outputKeyWithIntegerField_savesTheParsedMapWithALong() = runBlocking {
    // Act
    val saved = savedOutput("""{"n": 1}""", integerField("n"))

    // Assert
    assertThat(saved).isEqualTo(mapOf("n" to 1L))
  }

  @Test
  fun runAsync_outputKeyWithIntegerAbove2To53_savesTheRawText() = runBlocking {
    // Arrange: Gson would round this integer to 1234567890123456768.
    val text = """{"id": 1234567890123456789}"""

    // Act
    val saved = savedOutput(text, integerField("id"))

    // Assert
    assertThat(saved).isEqualTo(text)
  }

  private fun integerField(name: String) =
    Schema(type = Type.OBJECT, properties = mapOf(name to Schema(type = Type.INTEGER)))

  /** Runs an agent whose model answers [text] and returns what it saved under its `outputKey`. */
  private suspend fun savedOutput(text: String, schema: Schema): Any? {
    val model =
      DummyModel.createSequential(
        "gemini-3.8-flash",
        listOf(LlmResponse(content = modelMessage(text))),
      )
    val agent = LlmAgent(name = "agent", model = model, outputSchema = schema, outputKey = "result")
    val runner = InMemoryRunner(agent = agent)
    val key = SessionKey(runner.appName, "u", "s")
    val unused = runner.sessionService.createSession(key)
    val unusedEvents =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi")).toList()
    return runner.sessionService.getSession(key)?.state?.get("result")
  }
}
