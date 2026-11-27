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

import com.google.adk.kt.callbacks.OnToolErrorCallback
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.capturingLogs
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.FunctionCall
import com.google.common.truth.Truth.assertThat
import java.util.logging.Level
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class InvocationContextLoggingTest {

  @Test
  fun handleFunctionCallsWith_liveToolThrows_warnsWithoutTheExceptionMessage(): Unit = runBlocking {
    val tool = DummyTool(name = "check_calendar") { _, _ -> throw IllegalStateException(SECRET) }
    val context = testInvocationContext(agent = LlmAgent(name = "agent", model = DummyModel("m")))

    val (_, records) =
      capturingLogs(LOGGER, Level.WARNING) {
        context.handleFunctionCallsWith(
          listOf(FunctionCall(name = "check_calendar", id = "c1")),
          mapOf("check_calendar" to tool),
          onCallFailed = {},
        )
      }

    val record = records.single()
    assertThat(record.message)
      .isEqualTo("Tool check_calendar failed in a live call (IllegalStateException).")
    assertThat(record.thrown).isNull()
  }

  @Test
  fun handleFunctionCallsWith_liveCallToAnUnregisteredNameFails_warnsWithoutTheName(): Unit =
    runBlocking {
      val failing = OnToolErrorCallback { _, _, _, _ -> throw IllegalStateException(SECRET) }
      val agent =
        LlmAgent(name = "agent", model = DummyModel("m"), onToolErrorCallbacks = listOf(failing))
      val context = testInvocationContext(agent = agent)

      val (_, records) =
        capturingLogs(LOGGER, Level.WARNING) {
          context.handleFunctionCallsWith(
            listOf(FunctionCall(name = SECRET, id = "c1")),
            emptyMap(),
            onCallFailed = {},
          )
        }

      val record = records.single()
      assertThat(record.message).isEqualTo("A live tool call failed (IllegalStateException).")
      assertThat(record.thrown).isNull()
    }

  companion object {
    // Model or user data that must never reach the log.
    private const val SECRET = "private-user-text"
    private val LOGGER: String = InvocationContext::class.java.name
  }
}
