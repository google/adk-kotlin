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
package com.google.adk.kt.a2a.converters

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolResponse
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EventA2aSupportTest {

  @Test
  fun taskId_hasCustomMetadata_returnsTaskId() {
    val event = Event(author = "test", customMetadata = mapOf("a2a:task_id" to "task-123"))
    assertEquals("task-123", event.taskId)
  }

  @Test
  fun taskId_noCustomMetadata_returnsEmpty() {
    val event = Event(author = "test")
    assertEquals("", event.taskId)
  }

  @Test
  fun contextId_hasCustomMetadata_returnsContextId() {
    val event = Event(author = "test", customMetadata = mapOf("a2a:context_id" to "context-456"))
    assertEquals("context-456", event.contextId)
  }

  @Test
  fun contextId_noCustomMetadata_returnsEmpty() {
    val event = Event(author = "test")
    assertEquals("", event.contextId)
  }

  @Test
  fun taskIdAndContextId_legacyKeys_returnIds() {
    // Sessions recorded by earlier versions hold the ids under these keys.
    val event =
      Event(
        author = "test",
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "context-456"),
      )
    assertEquals("task-123", event.taskId)
    assertEquals("context-456", event.contextId)
  }

  @Test
  fun taskIdAndContextId_bothKeySetsDiffer_returnNewKeys() {
    // As in ADK Python, ids a hook or plugin wrote under the new keys are the ones used.
    val event =
      Event(
        author = "test",
        customMetadata =
          mapOf(
            "adk_task_id" to "task-123",
            "adk_context_id" to "context-456",
            "a2a:task_id" to "edited-task",
            "a2a:context_id" to "edited-context",
          ),
      )
    assertEquals("edited-task", event.taskId)
    assertEquals("edited-context", event.contextId)
  }

  @Test
  fun taskIdAndContextId_newKeysBlankedOrRemoved_ignoreLegacyKeys() {
    // A hook blanked a2a:task_id and removed a2a:context_id; as in ADK Python, no ids remain.
    val event =
      Event(
        author = "test",
        customMetadata =
          mapOf("adk_task_id" to "task-123", "adk_context_id" to "context-456", "a2a:task_id" to ""),
      )
    assertEquals("", event.taskId)
    assertEquals("", event.contextId)
  }

  @Test
  fun toEvent_mapsAllFields() {
    val fakeContent = modelMessage("hello")
    val fakeUsage =
      UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 20, totalTokenCount = 30)
    val fakeGrounding = GroundingMetadata()
    val fakeCitation = CitationMetadata()

    val response =
      com.google.adk.kt.models.LlmResponse(
        content = fakeContent,
        usageMetadata = fakeUsage,
        finishReason = FinishReason.STOP,
        errorMessage = "no error",
        partial = true,
        interrupted = true,
        modelVersion = "gemini-1.5",
        citationMetadata = fakeCitation,
        groundingMetadata = fakeGrounding,
      )

    val agent = DummyAgent(name = "test-agent")
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1")),
        runConfig = null,
      )

    val event = response.toEvent(context)

    assertEquals("inv-123", event.invocationId)
    assertEquals("test-agent", event.author)
    assertEquals(fakeContent, event.content)
    assertEquals(fakeUsage, event.usageMetadata)
    assertEquals(FinishReason.STOP, event.finishReason)
    assertEquals("no error", event.errorMessage)
    assertEquals(true, event.partial)
    assertEquals(true, event.interrupted)
    assertEquals("gemini-1.5", event.modelVersion)
    assertEquals(fakeCitation, event.citationMetadata)
    assertEquals(fakeGrounding, event.groundingMetadata)
  }

  @Test
  fun findUserFunctionCall_matchingCallExists_returnsCall() {
    val fc = FunctionCall(name = "my-func", id = "fc-id")
    val userEventWithCall =
      Event(author = Role.USER, content = userMessage(Part(functionCall = fc)))

    val userEventWithResponse =
      Event(author = Role.USER, content = userFunctionResponse(name = "my-func", id = "fc-id"))

    val events = listOf(userEventWithCall, userEventWithResponse)
    assertEquals(userEventWithCall, events.findUserFunctionCall())
  }

  @Test
  fun findUserFunctionCall_noMatchingCall_returnsNull() {
    val fc = FunctionCall(name = "my-func", id = "other-id")
    val userEventWithCall =
      Event(author = Role.USER, content = userMessage(Part(functionCall = fc)))

    val userEventWithResponse =
      Event(author = Role.USER, content = userFunctionResponse(name = "my-func", id = "fc-id"))

    val events = listOf(userEventWithCall, userEventWithResponse)
    assertNull(events.findUserFunctionCall())
  }

  @Test
  fun findUserFunctionCall_lastEventNotUser_returnsNull() {
    val fc = FunctionCall(name = "my-func", id = "fc-id")
    val userEventWithCall =
      Event(author = Role.USER, content = userMessage(Part(functionCall = fc)))

    val fr = FunctionResponse(name = "my-func", id = "fc-id")
    val agentEventWithResponse =
      Event(author = "agent", content = modelMessage(Part(functionResponse = fr)))

    val events = listOf(userEventWithCall, agentEventWithResponse)
    assertNull(events.findUserFunctionCall())
  }

  @Test
  fun updateEventMetadata_parsesGroundingMetadata_returnsEventWithGrounding() {
    val fakeGrounding = GroundingMetadata()
    val parser = FakeMetadataParser(responses = mapOf("raw_grounding_string" to fakeGrounding))

    val event = Event(author = "test")
    val clientMetadata = mapOf("adk_grounding_metadata" to "raw_grounding_string")

    val updatedEvent =
      event.updateEventMetadata(
        clientMetadata = clientMetadata,
        taskId = "task-1",
        contextId = "context-1",
        parser = parser,
      )

    assertEquals(fakeGrounding, updatedEvent.groundingMetadata)
  }

  @Test
  fun updateEventMetadata_readsErrorCode_returnsEventWithErrorCode() {
    val parser = FakeMetadataParser()

    val event = Event(author = "test")
    val clientMetadata = mapOf("adk_error_code" to "raw_error_code")

    val updatedEvent =
      event.updateEventMetadata(
        clientMetadata = clientMetadata,
        taskId = "task-1",
        contextId = "context-1",
        parser = parser,
      )

    assertEquals("raw_error_code", updatedEvent.errorCode)
  }

  @Test
  fun remoteCallAsUserPart_textPart_returnsFormattedText() {
    val part = Part(text = "hello")
    val result = remoteCallAsUserPart("agent1", part)
    assertEquals("[agent1] said: hello", result?.text)
  }

  @Test
  fun remoteCallAsUserPart_functionCallPart_returnsFormattedText() {
    val fc = FunctionCall(name = "my-func", args = mapOf("arg1" to "val1"))
    val part = Part(functionCall = fc)
    val result = remoteCallAsUserPart("agent1", part)
    assertEquals("[agent1] called tool my-func with parameters: {arg1=val1}", result?.text)
  }

  @Test
  fun remoteCallAsUserPart_functionResponsePart_returnsFormattedText() {
    val fr = FunctionResponse(name = "my-func", response = mapOf("res1" to "val2"))
    val part = Part(functionResponse = fr)
    val result = remoteCallAsUserPart("agent1", part)
    assertEquals("[agent1] my-func tool returned result: {res1=val2}", result?.text)
  }

  @Test
  fun remoteCallAsUserPart_inlineDataPart_returnsSamePart() {
    val part = Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1)))
    val result = remoteCallAsUserPart("agent1", part)
    assertSame(part, result)
  }

  @Test
  fun remoteCallAsUserPart_fileDataPart_returnsSamePart() {
    val part = Part(fileData = FileData(mimeType = "image/png", fileUri = "gs://bucket/image.png"))
    val result = remoteCallAsUserPart("agent1", part)
    assertSame(part, result)
  }

  @Test
  fun remoteCallAsUserPart_executableCodePart_returnsSamePart() {
    val part = Part(executableCode = ExecutableCode(code = "print(1)"))
    val result = remoteCallAsUserPart("agent1", part)
    assertSame(part, result)
  }

  @Test
  fun remoteCallAsUserPart_codeExecutionResultPart_returnsSamePart() {
    val part = Part(codeExecutionResult = CodeExecutionResult(output = "1"))
    val result = remoteCallAsUserPart("agent1", part)
    assertSame(part, result)
  }

  @Test
  fun remoteCallAsUserPart_emptyPart_returnsNull() {
    assertNull(remoteCallAsUserPart("agent1", Part()))
  }

  @Test
  fun presentAsUserMessage_emptyEvent_returnsUserEventWithAuthorOnly() {
    val event = Event(author = "agent1")
    val result = presentAsUserMessage(event, "ctx-123", "inv-123")
    assertEquals("user", result.author)
    assertEquals("inv-123", result.invocationId)
    assertNull(result.content)
  }

  @Test
  fun presentAsUserMessage_withContent_returnsFormattedUserEvent() {
    val event = Event(author = "agent1", content = modelMessage("hello"))
    val result = presentAsUserMessage(event, "ctx-123", "inv-123")
    assertEquals("user", result.author)
    assertEquals("inv-123", result.invocationId)
    assertEquals(Role.MODEL, result.content?.role)
    assertEquals(2, result.content?.parts?.size)
    assertEquals("For context:", result.content?.parts?.get(0)?.text)
    assertEquals("[agent1] said: hello", result.content?.parts?.get(1)?.text)
  }

  @Test
  fun presentAsUserMessage_blankTextSignatureAndServerToolParts_relaysOnlyText() {
    val parts =
      listOf(
        Part(text = "hello"),
        Part(text = " "),
        Part(thoughtSignature = byteArrayOf(1)),
        Part(toolCall = ToolCall(id = "t1")),
        Part(toolResponse = ToolResponse(id = "t1")),
      )
    val event = Event(author = "agent1", content = Content(role = Role.MODEL, parts = parts))
    val result = presentAsUserMessage(event, "ctx-123", "inv-123")
    assertEquals(
      listOf("For context:", "[agent1] said: hello"),
      result.content?.parts?.map { it.text },
    )
  }

  @Test
  fun presentAsUserMessage_setsContextIdInCustomMetadata() {
    val event = Event(author = "agent1")
    val result = presentAsUserMessage(event, "ctx-123", "inv-123")
    assertEquals("ctx-123", result.contextId)
  }

  @Test
  fun emptyEvent_returnsCorrectEvent() {
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    val agent = DummyAgent(name = "agent1")
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        branch = "branch1",
        session = session,
        runConfig = null,
      )
    val result = emptyEvent(context)
    assertEquals("inv-123", result.invocationId)
    assertEquals("agent1", result.author)
    assertEquals("branch1", result.branch)
    assertEquals(Role.USER, result.content?.role)
    assertEquals(0, result.content?.parts?.size)
  }

  @Test
  fun remoteAgentEvent_returnsCorrectEvent() {
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    val agent = DummyAgent(name = "agent1")
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        branch = "branch1",
        session = session,
        runConfig = null,
      )
    val result = remoteAgentEvent(context)
    assertEquals("inv-123", result.invocationId)
    assertEquals("agent1", result.author)
    assertEquals("branch1", result.branch)
    assertNull(result.content)
  }

  @Test
  fun extractPreprocessedEvents_emptyEvents_returnsEmptyList() {
    val agent = DummyAgent(name = "my-agent")
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1")),
        runConfig = null,
      )
    val result = context.extractPreprocessedEvents()
    assertSame(emptyList(), result)
  }

  @Test
  fun extractPreprocessedEvents_dropsEventsBeforeLastAgentResponse() {
    val agent = DummyAgent(name = "my-agent")
    val event1 = Event(author = Role.USER, content = userMessage("hi"))
    val event2 = Event(author = "my-agent", content = modelMessage("hello"))
    val event3 = Event(author = Role.USER, content = userMessage("how are you?"))

    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(event1, event2, event3))

    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = context.extractPreprocessedEvents()
    assertEquals(1, result.size)
    assertEquals(event3, result[0])
  }

  @Test
  fun extractPreprocessedEvents_afterFailedRequest_keepsItsTurn() {
    val agent = DummyAgent(name = "my-agent")
    val failedRequest =
      Event(
        author = "my-agent",
        errorMessage = "A2A request failed (IOException)",
        customMetadata = mapOf("a2a:request" to "{}"),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(
      listOf(
        Event(author = "my-agent", content = modelMessage("Which color?")),
        Event(author = Role.USER, content = userMessage("Blue")),
        failedRequest,
        Event(author = Role.USER, content = userMessage("Green")),
      )
    )
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = context.extractPreprocessedEvents()

    assertEquals(
      listOf("Blue", "Green"),
      result.flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.text },
    )
  }

  @Test
  fun extractPreprocessedEvents_rephrasesOtherAgentMessages() {
    val agent = DummyAgent(name = "my-agent")
    val event1 = Event(author = "other-agent", content = modelMessage("data"))

    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.add(event1)

    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = context.extractPreprocessedEvents()
    assertEquals(1, result.size)
    assertEquals("user", result[0].author)
    assertEquals("[other-agent] said: data", result[0].content?.parts?.get(1)?.text)
  }

  @Test
  fun extractPreprocessedEvents_dropsCredentialParts() {
    val agent = DummyAgent(name = "my-agent")
    val credentialCall =
      FunctionCall(
        name = "adk_request_credential",
        id = "cred-id",
        args = mapOf("authConfig" to AUTH_CONFIG),
      )
    // Only the call id marks this answer as a credential.
    val credentialAnswer =
      FunctionResponse(name = "my-auth", id = "cred-id", response = mapOf("token" to "t"))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(
      listOf(
        Event(author = "other-agent", content = modelMessage(Part(text = "Signing in."))),
        Event(author = "other-agent", content = modelMessage(Part(functionCall = credentialCall))),
        Event(
          author = Role.USER,
          content =
            Content(
              role = Role.USER,
              parts = listOf(Part(functionResponse = credentialAnswer), Part(text = "Done.")),
            ),
        ),
      )
    )
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = context.extractPreprocessedEvents()

    assertEquals(
      listOf("For context:", "[other-agent] said: Signing in.", "Done."),
      result.flatMap { it.content?.parts.orEmpty() }.map { it.text },
    )
  }

  @Test
  fun withoutCredentialParts_credentialRequestCall_dropsIt() {
    val call = FunctionCall(name = "adk_request_credential")
    assertTrue(Part(functionCall = call).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun withoutCredentialParts_callWithAuthConfigArgument_dropsIt() {
    val call = FunctionCall(name = "my-func", args = mapOf("auth_config" to AUTH_CONFIG))
    assertTrue(Part(functionCall = call).isDroppedFromEvent(emptySet()))
  }

  // The AuthConfig is nested under authConfig, so a top-level auth_scheme is an ordinary argument.
  @Test
  fun withoutCredentialParts_callWithTopLevelAuthSchemeArgument_keepsIt() {
    val call = FunctionCall(name = "my-func", args = mapOf("auth_scheme" to "basic"))
    assertFalse(Part(functionCall = call).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun withoutCredentialParts_callWhoseAuthConfigArgumentIsNotAnAuthConfig_keepsIt() {
    val call = FunctionCall(name = "my-func", args = mapOf("authConfig" to "profile-1"))
    assertFalse(Part(functionCall = call).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun withoutCredentialParts_responseHoldingAuthConfig_dropsIt() {
    val response = FunctionResponse(name = "my-func", response = AUTH_CONFIG)
    assertTrue(Part(functionResponse = response).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun withoutCredentialParts_responseHoldingWrappedSnakeCaseAuthConfig_dropsIt() {
    val authConfig = mapOf("raw_auth_credential" to mapOf("api_key" to "k"))
    val response = FunctionResponse(name = "my-func", response = mapOf("result" to authConfig))
    assertTrue(Part(functionResponse = response).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun withoutCredentialParts_responseNamedForCredentialRequest_dropsIt() {
    val response = FunctionResponse(name = "adk_request_credential", response = mapOf("ok" to true))
    assertTrue(Part(functionResponse = response).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun withoutCredentialParts_responseToCredentialCall_dropsIt() {
    val response = FunctionResponse(name = "my-func", id = "fc-id", response = mapOf("ok" to true))
    assertTrue(Part(functionResponse = response).isDroppedFromEvent(setOf("fc-id")))
  }

  @Test
  fun withoutCredentialParts_ordinaryResponse_keepsIt() {
    val response = FunctionResponse(name = "my-func", response = mapOf("result" to "fine"))
    assertFalse(Part(functionResponse = response).isDroppedFromEvent(emptySet()))
  }

  @Test
  fun extractPreprocessedEvents_idLessCredentialRequest_dropsIdLessAnswers() {
    val agent = DummyAgent(name = "my-agent")
    val answer = FunctionResponse(name = "my-auth", response = mapOf("token" to "t"))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(
      listOf(
        Event(
          author = "other-agent",
          content = modelMessage(Part(functionCall = FunctionCall(name = "adk_request_credential"))),
        ),
        Event(
          author = Role.USER,
          content = Content(role = Role.USER, parts = listOf(Part(functionResponse = answer))),
        ),
      )
    )
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    assertEquals(
      emptyList(),
      context.extractPreprocessedEvents().flatMap { it.content?.parts.orEmpty() },
    )
  }

  // An id-less answer cannot be paired with its request, so it fails closed.
  @Test
  fun withoutCredentialParts_idLessResponseWithIdLessCredentialRequest_dropsIt() {
    val response = FunctionResponse(name = "my-func", response = mapOf("ok" to true))
    assertTrue(Part(functionResponse = response).isDroppedFromEvent(setOf(null)))
  }

  @Test
  fun withoutCredentialParts_idLessResponseWithIdentifiedCredentialRequest_keepsIt() {
    val response = FunctionResponse(name = "my-func", response = mapOf("ok" to true))
    assertFalse(Part(functionResponse = response).isDroppedFromEvent(setOf("cred-id")))
  }

  @Test
  fun extractPreprocessedEvents_propagatesContextIdFromLastAgentResponse() {
    val agent = DummyAgent(name = "my-agent")
    val lastAgentResponse =
      Event(author = "my-agent", customMetadata = mapOf("a2a:context_id" to "parent-context-id"))
    val otherAgentCall = Event(author = "other-agent", content = modelMessage("work done"))

    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(lastAgentResponse, otherAgentCall))

    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = context.extractPreprocessedEvents()
    assertEquals(1, result.size)
    assertEquals("user", result[0].author)
    assertEquals("parent-context-id", result[0].customMetadata?.get("a2a:context_id"))
  }

  private fun Part.isDroppedFromEvent(credentialCallIds: Set<String?>): Boolean {
    val event = Event(author = "agent1", content = Content(role = Role.MODEL, parts = listOf(this)))
    return event.withoutCredentialParts(credentialCallIds).content?.parts.isNullOrEmpty()
  }

  companion object {
    private val AUTH_CONFIG =
      mapOf(
        "authScheme" to mapOf("type" to "oauth2"),
        "rawAuthCredential" to mapOf("oauth2" to mapOf("clientSecret" to "s3cr3t")),
      )
  }
}

class FakeMetadataParser(private val responses: Map<Any?, Any?> = emptyMap()) : A2AMetadataParser {
  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> parse(metadata: Any?, clazz: kotlin.reflect.KClass<T>): T? {
    return responses[metadata] as? T
  }
}
