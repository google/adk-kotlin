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

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.testing.DummyArtifactService
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class ToolContextTest {

  @Test
  fun toolContext_initialization_setsProperties() {
    val invocationContext = testInvocationContext()
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
    assertSame(eventActions, context.actions)
    assertEquals("function_call_id", context.functionCallId)
    assertEquals(toolConfirmation, context.toolConfirmation)
    assertEquals("event_id", context.eventId)
  }

  @Test
  fun toolContext_initializationWithDefaults_setsProperties() {
    val invocationContext = testInvocationContext()
    val context = ToolContext(invocationContext = invocationContext)

    assertEquals(invocationContext, context.invocationContext)
    assertTrue(context.actions.requestedToolConfirmations.isEmpty())
    assertNull(context.functionCallId)
    assertNull(context.toolConfirmation)
    assertNull(context.eventId)
  }

  @Test
  fun requestConfirmation_withHintAndPayload_requestsConfirmation() {
    val context = testToolContext(functionCallId = "function_call_id")

    context.requestConfirmation(hint = "Please confirm", payload = "Test Payload")

    val confirmation = context.actions.requestedToolConfirmations["function_call_id"]
    assertNotNull(confirmation)
    assertEquals("Please confirm", confirmation.hint)
    assertFalse(confirmation.confirmed)
    assertEquals("Test Payload", confirmation.payload)
  }

  @Test
  fun requestConfirmation_defaultArguments_requestsConfirmationWithDefaults() {
    val context = testToolContext(functionCallId = "function_call_id")

    context.requestConfirmation()

    val confirmation = context.actions.requestedToolConfirmations["function_call_id"]
    assertNotNull(confirmation)
    assertNull(confirmation.hint)
    assertFalse(confirmation.confirmed)
    assertNull(confirmation.payload)
  }

  @Test
  fun requestConfirmation_nullFunctionCallId_throwsException() {
    val context = testToolContext()

    val exception = assertFailsWith<IllegalStateException> { context.requestConfirmation() }

    assertEquals("functionCallId is not set.", exception.message)
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun requestCredential_apiKeyConfig_requestsItWithoutTheKey() {
    val context = testToolContext(functionCallId = "function_call_id")

    context.requestCredential(API_KEY_CONFIG)

    assertEquals(
      mapOf("function_call_id" to API_KEY_CONFIG.copy(rawAuthCredential = AuthCredential.ApiKey())),
      context.actions.requestedAuthConfigs,
    )
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun requestCredential_nullFunctionCallId_throwsException() {
    val context = testToolContext()

    val exception =
      assertFailsWith<IllegalStateException> { context.requestCredential(API_KEY_CONFIG) }

    assertEquals("functionCallId is not set.", exception.message)
    assertTrue(context.actions.requestedAuthConfigs.isEmpty())
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun getAuthResponse_noReply_returnsNull() = runBlocking {
    assertNull(testToolContext().getAuthResponse(API_KEY_CONFIG))
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun getAuthResponse_replyInPendingState_returnsIt() = runBlocking {
    val context = testToolContext(functionCallId = "function_call_id")
    context.updateState("temp:${API_KEY_CONFIG.credentialKey}", "sk-reply")

    assertEquals(AuthCredential.ApiKey(Secret("sk-reply")), context.getAuthResponse(API_KEY_CONFIG))
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

    val context = testToolContext(testInvocationContext(artifactService = artifactService))

    val artifacts = context.listArtifacts()
    assertEquals(listOf("file1.txt", "file2.jpg"), artifacts)
  }

  @Test
  fun listArtifacts_noService_returnsEmptyList() = runTest {
    val context = testToolContext()

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

    val context = testToolContext(testInvocationContext(artifactService = artifactService))

    val part = context.loadArtifact("file1.txt")
    assertEquals(expectedPart, part)
  }

  @Test
  fun loadArtifact_noService_returnsNull() = runTest {
    val context = testToolContext()

    val part = context.loadArtifact("file1.txt")
    assertNull(part)
  }

  private companion object {
    @OptIn(ExperimentalAuthApi::class)
    val API_KEY_CONFIG =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        AuthCredential.ApiKey(Secret("sk-123")),
      )
  }
}
