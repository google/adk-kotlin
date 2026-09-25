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
package com.google.adk.kt.a2a.agent

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class BaseRemoteA2AAgentTest {

  private class TestRemoteAgent(name: String) : BaseRemoteA2AAgent(name = name) {
    override val isStreamingEnabled: Boolean = true

    override fun createA2aCallbackFlow(
      context: InvocationContext,
      outboundEvent: Event,
    ): Flow<Event> = emptyFlow()

    fun testPrepareOutboundEvent(context: InvocationContext): Event = prepareOutboundEvent(context)

    fun testAddA2AMetadata(
      event: Event,
      debugRequest: Result<String>? = null,
      debugResponse: Result<String>? = null,
    ): Event = addA2AMetadata(event, debugRequest, debugResponse)
  }

  @Test
  fun prepareOutboundEvent_userFunctionCallExists_returnsResponseEvent() {
    val agent = TestRemoteAgent("my-agent")
    val fc = FunctionCall(name = "my-func", id = "fc-id")
    val userCall =
      Event(
        author = Role.USER,
        content = userMessage(Part(functionCall = fc)),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val userResponse =
      Event(author = Role.USER, content = userFunctionResponse(name = "my-func", id = "fc-id"))

    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(userCall, userResponse))

    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)
    assertEquals(Role.USER, result.author)
    assertEquals(userResponse.content, result.content)
    assertEquals("task-123", result.customMetadata?.get("adk_task_id"))
    assertEquals("ctx-456", result.customMetadata?.get("adk_context_id"))
  }

  @Test
  fun prepareOutboundEvent_noUserCall_returnsFlattenedParts() {
    val agent = TestRemoteAgent("my-agent")
    val event1 = Event(author = Role.USER, content = userMessage("hi"))

    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.add(event1)

    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)
    assertEquals(Role.USER, result.author)
    assertEquals(1, result.content?.parts?.size)
    assertEquals("hi", result.content?.parts?.get(0)?.text)
  }

  @Test
  fun prepareOutboundEvent_noUserCall_propagatesContextIdFromLastAgentResponse() {
    val agent = TestRemoteAgent("my-agent")
    val lastAgentResponse =
      Event(
        author = "my-agent",
        content = modelMessage("reply"),
        customMetadata = mapOf("adk_context_id" to "parent-context-123"),
      )
    val nextUserEvent = Event(author = Role.USER, content = userMessage("how are you?"))

    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(lastAgentResponse, nextUserEvent))

    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)
    assertEquals(Role.USER, result.author)
    assertEquals("parent-context-123", result.customMetadata?.get("adk_context_id"))
  }

  @Test
  fun prepareOutboundEvent_whenSessionEmpty_returnsEmptyParts() {
    val agent = TestRemoteAgent("my-agent")
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)
    assertEquals(Role.USER, result.author)
    assertEquals(true, result.content?.parts?.isEmpty() ?: true)
  }

  @Test
  fun prepareOutboundEvent_whenLastEventNotUser_returnsEmptyParts() {
    val agent = TestRemoteAgent("my-agent")
    val event = Event(author = "my-agent", content = modelMessage("reply"))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.add(event)
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)
    assertEquals(Role.USER, result.author)
    assertEquals(true, result.content?.parts?.isEmpty() ?: true)
  }

  @Test
  fun prepareOutboundEvent_whenLastEventUserEmptyParts_returnsEmptyParts() {
    val agent = TestRemoteAgent("my-agent")
    val event = Event(author = Role.USER, content = Content(role = Role.USER, parts = emptyList()))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.add(event)
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)
    assertEquals(Role.USER, result.author)
    assertEquals(true, result.content?.parts?.isEmpty() ?: true)
  }

  @Test
  fun addA2AMetadata_addsRequestAndResponse() {
    val agent = TestRemoteAgent("test-agent")
    val event = Event(author = "user", turnComplete = true)
    val result =
      agent.testAddA2AMetadata(
        event = event,
        debugRequest = Result.success("{\"req\":1}"),
        debugResponse = Result.success("{\"res\":2}"),
      )

    assertEquals("{\"req\":1}", result.customMetadata?.get("a2a:request"))
    assertEquals("{\"res\":2}", result.customMetadata?.get("a2a:response"))
  }

  @Test
  fun addA2AMetadata_handlesNulls() {
    val agent = TestRemoteAgent("test-agent")
    val event = Event(author = "user")
    val result = agent.testAddA2AMetadata(event, null, null)

    assertEquals(event.customMetadata ?: emptyMap<String, Any>(), result.customMetadata)
  }

  @Test
  fun addA2AMetadata_propagatesSerializationErrors() {
    val agent = TestRemoteAgent("test-agent")
    val event = Event(author = "user", turnComplete = true)
    val result =
      agent.testAddA2AMetadata(
        event = event,
        debugRequest = Result.failure(Exception("Failed to serialize request")),
        debugResponse = Result.failure(Exception("Failed to serialize response")),
      )

    assertEquals(
      "Failed to serialize request",
      result.customMetadata?.get(BaseRemoteA2AAgent.REQUEST_ERROR),
    )
    assertEquals(
      "Failed to serialize response",
      result.customMetadata?.get(BaseRemoteA2AAgent.RESPONSE_ERROR),
    )
  }
}
