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

package com.google.adk.kt.models

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.types.Content
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * Covers the optional-capability default on [Model.connect], and the blocking
 * [LiveConnection.close] bridge that runs [LiveConnection.closeSession].
 */
class ModelTest {

  /** Holds no transport; this test only needs to count teardowns. */
  private class CountingLiveConnection : LiveConnection {
    var closeSessionCalls = 0

    override suspend fun sendHistory(history: List<Content>) {}

    override suspend fun sendContent(content: Content, partial: Boolean) {}

    override suspend fun sendRealtime(input: RealtimeInput) {}

    override fun receive(): Flow<LlmResponse> = emptyFlow()

    override suspend fun closeSession() {
      closeSessionCalls++
    }
  }

  @Test
  fun connect_modelWithoutLiveSupport_throwsNamingTheModel(): Unit = runBlocking {
    val failure =
      assertFailsWith<UnsupportedOperationException> {
        DummyModel("non-live-model").connect(LlmRequest())
      }

    assertContains(
      failure.message.orEmpty(),
      "non-live-model",
      message = "the refusal must name the model so a caller knows which one lacks live support",
    )
  }

  @Test
  fun close_onALiveConnection_runsCloseSessionOnce() {
    val connection = CountingLiveConnection()

    connection.close()

    assertEquals(1, connection.closeSessionCalls)
  }

  @Test
  fun capabilities_byDefault_followTheGemini2NameRule() {
    // Arrange: name to whether a response schema may go beside tools, as before capabilities.
    val expected =
      mapOf(
        "gemini-2.5-flash" to false,
        "models/gemini-2.0-flash" to false,
        "projects/p/locations/global/publishers/google/models/gemini-2.5-pro" to false,
        "gemini-3.8-flash" to true,
        "models/gemini-3.8-flash" to true,
        "claude-sonnet-4-5" to true,
        "GenaiPrompt" to true,
        "gemini-nano" to true,
        "my-model" to true,
      )

    // Act
    val reported = expected.keys.associateWith { DummyModel(it).capabilities.outputSchemaAndTools }
    val decided = expected.keys.associateWith { DummyModel(it).takesOutputSchemaWithTools }

    // Assert
    assertEquals(expected, reported)
    assertEquals(expected, decided)
  }

  @Test
  fun takesOutputSchemaWithTools_renamedDelegatingWrapper_followsItsDelegate() {
    // Arrange: `Model by delegate` forwards `capabilities`, so each reports its delegate's value.
    val gemini2Name =
      object : Model by DummyModel("my-model") {
        override val name = "gemini-2.5-flash"
      }
    val otherName =
      object : Model by DummyModel("gemini-2.5-flash") {
        override val name = "my-model"
      }

    // Act & Assert
    assertEquals(true, gemini2Name.takesOutputSchemaWithTools)
    assertEquals(false, otherName.takesOutputSchemaWithTools)
  }

  @Test
  fun takesOutputSchemaWithTools_override_winsOverTheName() {
    // Arrange
    val forcedOff =
      object : Model by DummyModel("my-model") {
        override val capabilities = LlmCapabilities(outputSchemaAndTools = false)
      }
    val forcedOn =
      object : Model by DummyModel("gemini-2.5-flash") {
        override val capabilities = LlmCapabilities(outputSchemaAndTools = true)
      }

    // Act & Assert
    assertEquals(false, forcedOff.takesOutputSchemaWithTools)
    assertEquals(true, forcedOn.takesOutputSchemaWithTools)
  }

  @Test
  fun takesOutputSchemaWithTools_nullCapabilities_isDecidedByTheName() {
    // Arrange: a getter that breaks its non-null type, as an unstubbed Mockito mock does.
    val model =
      object : Model by DummyModel("gemini-2.5-flash") {
        override val capabilities: LlmCapabilities
          get() = uncheckedNull()
      }

    // Act & Assert
    assertEquals(false, model.takesOutputSchemaWithTools)
  }

  @Suppress("UNCHECKED_CAST") private fun <T> uncheckedNull(): T = null as T
}
