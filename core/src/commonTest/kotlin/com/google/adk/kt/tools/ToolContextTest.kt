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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyArtifactService
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ToolContextTest {

  @Test
  fun toolContext_initialization_setsProperties() {
    val invocationContext = getTestInvocationContext()
    val eventActions = EventActions()
    val toolConfirmation = ToolConfirmation(confirmed = true, hint = "hint")
    val context =
      ToolContext(
        invocationContext = invocationContext,
        actions = eventActions,
        functionCallId = "function_call_id",
        toolConfirmation = toolConfirmation,
        eventId = "event_id",
      )

    assertEquals(invocationContext, context.invocationContext)
    assertEquals(eventActions, context.actions)
    assertEquals("function_call_id", context.functionCallId)
    assertEquals(toolConfirmation, context.toolConfirmation)
    assertEquals("event_id", context.eventId)
  }

  @Test
  fun toolContext_initializationWithDefaults_setsProperties() {
    val invocationContext = getTestInvocationContext()
    val context = ToolContext(invocationContext = invocationContext)

    assertEquals(invocationContext, context.invocationContext)
    assertTrue(context.actions.requestedToolConfirmations.isEmpty())
    assertNull(context.functionCallId)
    assertNull(context.toolConfirmation)
    assertNull(context.eventId)
  }

  @Test
  fun requestConfirmation_withHintAndPayload_requestsConfirmation() {
    val invocationContext = getTestInvocationContext()
    val context =
      ToolContext(invocationContext = invocationContext, functionCallId = "function_call_id")

    context.requestConfirmation(hint = "Please confirm", payload = "Test Payload")

    val confirmation = context.actions.requestedToolConfirmations["function_call_id"]
    assertNotNull(confirmation)
    assertEquals("Please confirm", confirmation.hint)
    assertFalse(confirmation.confirmed)
    assertEquals("Test Payload", confirmation.payload)
  }

  @Test
  fun requestConfirmation_defaultArguments_requestsConfirmationWithDefaults() {
    val invocationContext = getTestInvocationContext()
    val context =
      ToolContext(invocationContext = invocationContext, functionCallId = "function_call_id")

    context.requestConfirmation()

    val confirmation = context.actions.requestedToolConfirmations["function_call_id"]
    assertNotNull(confirmation)
    assertNull(confirmation.hint)
    assertFalse(confirmation.confirmed)
    assertNull(confirmation.payload)
  }

  @Test
  fun requestConfirmation_nullFunctionCallId_throwsException() {
    val invocationContext = getTestInvocationContext()
    val context = ToolContext(invocationContext = invocationContext)

    val exception = assertFailsWith<IllegalStateException> { context.requestConfirmation() }

    assertEquals("functionCallId is not set.", exception.message)
  }

  @Test
  fun listArtifacts_returnsFilenames() = runTest {
    val artifactService =
      DummyArtifactService(
        onListArtifactKeys = { sessionKey ->
          assertEquals("test_app_name", sessionKey.appName)
          assertEquals("test_user_id", sessionKey.userId)
          assertEquals("test_session_id", sessionKey.id)
          listOf("file1.txt", "file2.jpg")
        }
      )

    val invocationContext = getTestInvocationContext(artifactService)
    val context = ToolContext(invocationContext = invocationContext)

    val artifacts = context.listArtifacts()
    assertEquals(listOf("file1.txt", "file2.jpg"), artifacts)
  }

  @Test
  fun listArtifacts_noService_returnsEmptyList() = runTest {
    val invocationContext = getTestInvocationContext(null)
    val context = ToolContext(invocationContext = invocationContext)

    val artifacts = context.listArtifacts()
    assertTrue(artifacts.isEmpty())
  }

  @Test
  fun loadArtifact_returnsPart() = runTest {
    val expectedPart = Part(text = "content")
    val artifactService =
      DummyArtifactService(
        onLoadArtifact = { sessionKey, filename, _ ->
          assertEquals("test_app_name", sessionKey.appName)
          assertEquals("test_user_id", sessionKey.userId)
          assertEquals("test_session_id", sessionKey.id)
          assertEquals("file1.txt", filename)
          expectedPart
        }
      )

    val invocationContext = getTestInvocationContext(artifactService)
    val context = ToolContext(invocationContext = invocationContext)

    val part = context.loadArtifact("file1.txt")
    assertEquals(expectedPart, part)
  }

  @Test
  fun loadArtifact_noService_returnsNull() = runTest {
    val invocationContext = getTestInvocationContext(null)
    val context = ToolContext(invocationContext = invocationContext)

    val part = context.loadArtifact("file1.txt")
    assertNull(part)
  }

  private fun getTestInvocationContext(artifactService: ArtifactService? = null) =
    InvocationContext(
      session = testSession(),
      runConfig = com.google.adk.kt.agents.RunConfig(), // Add default RunConfig
      agent = DummyAgent("test-agent"),
      sessionService = InMemorySessionService(), // Add InMemorySessionService
      invocationId = "test-invocation-id", // Add invocationId
      artifactService = artifactService,
    )
}
