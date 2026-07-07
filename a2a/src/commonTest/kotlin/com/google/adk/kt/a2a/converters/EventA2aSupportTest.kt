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
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class EventA2aSupportTest {

  @Test
  fun taskId_hasCustomMetadata_returnsTaskId() {
    val event = Event(author = "test", customMetadata = mapOf("adk_task_id" to "task-123"))
    assertEquals("task-123", event.taskId)
  }

  @Test
  fun taskId_noCustomMetadata_returnsEmpty() {
    val event = Event(author = "test")
    assertEquals("", event.taskId)
  }

  @Test
  fun contextId_hasCustomMetadata_returnsContextId() {
    val event = Event(author = "test", customMetadata = mapOf("adk_context_id" to "context-456"))
    assertEquals("context-456", event.contextId)
  }

  @Test
  fun contextId_noCustomMetadata_returnsEmpty() {
    val event = Event(author = "test")
    assertEquals("", event.contextId)
  }

  @Test
  fun toEvent_mapsAllFields() {
    val fakeContent = Content(role = Role.MODEL, parts = listOf(Part(text = "hello")))
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
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(functionCall = fc))),
      )

    val fr = FunctionResponse(name = "my-func", id = "fc-id")
    val userEventWithResponse =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(functionResponse = fr))),
      )

    val events = listOf(userEventWithCall, userEventWithResponse)
    assertEquals(userEventWithCall, events.findUserFunctionCall())
  }

  @Test
  fun findUserFunctionCall_noMatchingCall_returnsNull() {
    val fc = FunctionCall(name = "my-func", id = "other-id")
    val userEventWithCall =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(functionCall = fc))),
      )

    val fr = FunctionResponse(name = "my-func", id = "fc-id")
    val userEventWithResponse =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(functionResponse = fr))),
      )

    val events = listOf(userEventWithCall, userEventWithResponse)
    assertNull(events.findUserFunctionCall())
  }

  @Test
  fun findUserFunctionCall_lastEventNotUser_returnsNull() {
    val fc = FunctionCall(name = "my-func", id = "fc-id")
    val userEventWithCall =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(functionCall = fc))),
      )

    val fr = FunctionResponse(name = "my-func", id = "fc-id")
    val agentEventWithResponse =
      Event(
        author = "agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(functionResponse = fr))),
      )

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
    assertEquals("[agent1] said: hello", result.text)
  }

  @Test
  fun remoteCallAsUserPart_functionCallPart_returnsFormattedText() {
    val fc = FunctionCall(name = "my-func", args = mapOf("arg1" to "val1"))
    val part = Part(functionCall = fc)
    val result = remoteCallAsUserPart("agent1", part)
    assertEquals("[agent1] called tool my-func with parameters: {arg1=val1}", result.text)
  }

  @Test
  fun remoteCallAsUserPart_functionResponsePart_returnsFormattedText() {
    val fr = FunctionResponse(name = "my-func", response = mapOf("res1" to "val2"))
    val part = Part(functionResponse = fr)
    val result = remoteCallAsUserPart("agent1", part)
    assertEquals("[agent1] my-func tool returned result: {res1=val2}", result.text)
  }

  @Test
  fun remoteCallAsUserPart_emptyPart_returnsSamePart() {
    val part = Part()
    val result = remoteCallAsUserPart("agent1", part)
    assertEquals(part, result)
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
    val event =
      Event(
        author = "agent1",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "hello"))),
      )
    val result = presentAsUserMessage(event, "ctx-123", "inv-123")
    assertEquals("user", result.author)
    assertEquals("inv-123", result.invocationId)
    assertEquals(Role.MODEL, result.content?.role)
    assertEquals(2, result.content?.parts?.size)
    assertEquals("For context:", result.content?.parts?.get(0)?.text)
    assertEquals("[agent1] said: hello", result.content?.parts?.get(1)?.text)
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
    val event1 =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(text = "hi"))),
      )
    val event2 =
      Event(
        author = "my-agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "hello"))),
      )
    val event3 =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(text = "how are you?"))),
      )

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
  fun extractPreprocessedEvents_rephrasesOtherAgentMessages() {
    val agent = DummyAgent(name = "my-agent")
    val event1 =
      Event(
        author = "other-agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "data"))),
      )

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
  fun extractPreprocessedEvents_propagatesContextIdFromLastAgentResponse() {
    val agent = DummyAgent(name = "my-agent")
    val lastAgentResponse =
      Event(author = "my-agent", customMetadata = mapOf("adk_context_id" to "parent-context-id"))
    val otherAgentCall =
      Event(
        author = "other-agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "work done"))),
      )

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
    assertEquals("parent-context-id", result[0].contextId)
  }
}

class FakeMetadataParser(private val responses: Map<Any?, Any?> = emptyMap()) : A2AMetadataParser {
  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> parse(metadata: Any?, clazz: kotlin.reflect.KClass<T>): T? {
    return responses[metadata] as? T
  }
}
