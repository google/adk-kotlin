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
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private val MOCK_INPUT_CALL =
  FunctionCall(name = "mock_function_call_for_required_user_input", id = "mock-id")

private fun mockInputAnswer(response: Map<String, Any?>) =
  FunctionResponse(name = MOCK_INPUT_CALL.name, response = response, id = MOCK_INPUT_CALL.id)

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
  fun prepareOutboundEvent_answerToCredentialCall_isLeftOut() {
    val agent = TestRemoteAgent("my-agent")
    val remoteCalls =
      Event(
        author = "my-agent",
        content =
          modelMessage(
            Part(functionCall = FunctionCall(name = "adk_request_credential", id = "cred-id")),
            Part(functionCall = FunctionCall(name = "lookup", id = "fc-id")),
          ),
      )
    val credential =
      FunctionResponse(name = "my-auth", id = "cred-id", response = mapOf("token" to "t"))
    val lookup = FunctionResponse(name = "lookup", id = "fc-id", response = mapOf("ok" to true))
    val userResponse =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts = listOf(Part(functionResponse = credential), Part(functionResponse = lookup)),
          ),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(remoteCalls, userResponse))
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals(listOf(lookup), result.content?.parts?.map { it.functionResponse })
  }

  @Test
  fun prepareOutboundEvent_onlyCredentialAnswer_fallsBackToScrubbedHistory() {
    val agent = TestRemoteAgent("my-agent")
    val remoteCall =
      Event(
        author = "my-agent",
        content =
          modelMessage(
            Part(functionCall = FunctionCall(name = "adk_request_credential", id = "cred-id"))
          ),
      )
    val userResponse =
      Event(
        author = Role.USER,
        content =
          userFunctionResponse(name = "my-auth", id = "cred-id", response = mapOf("token" to "t")),
      )
    val otherAgentEvent =
      Event(author = "other-agent", content = modelMessage("Use the saved key."))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(remoteCall, otherAgentEvent, userResponse))
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals(
      listOf("For context:", "[other-agent] said: Use the saved key."),
      result.content?.parts?.map { it.text },
    )
  }

  @Test
  fun prepareOutboundEvent_answerToMockInputCall_sendsAnswerAsText() {
    val agent = TestRemoteAgent("my-agent")
    val answer = Part(functionResponse = mockInputAnswer(mapOf("result" to "Blue")))

    val result =
      agent.testPrepareOutboundEvent(contextAnswering(agent, listOf(MOCK_INPUT_CALL), answer))

    assertEquals(listOf(Part(text = "Blue")), result.content?.parts)
    assertEquals("task-123", result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_structuredAnswerToMockInputCall_sendsItAsJson() {
    val agent = TestRemoteAgent("my-agent")
    val answer = Part(functionResponse = mockInputAnswer(mapOf("color" to "blue", "shade" to 3)))

    val result =
      agent.testPrepareOutboundEvent(contextAnswering(agent, listOf(MOCK_INPUT_CALL), answer))

    assertEquals(listOf("""{"color":"blue","shade":3}"""), result.content?.parts?.map { it.text })
  }

  @Test
  fun prepareOutboundEvent_singleKeyAnswerOtherThanResult_sendsItAsJsonOnThePausedTask() {
    val agent = TestRemoteAgent("my-agent")
    val answer = Part(functionResponse = mockInputAnswer(mapOf("company_name" to "Okta")))

    val result =
      agent.testPrepareOutboundEvent(contextAnswering(agent, listOf(MOCK_INPUT_CALL), answer))

    assertEquals(listOf(Part(text = """{"company_name":"Okta"}""")), result.content?.parts)
    assertEquals("task-123", result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_emptyAnswerToMockInputCall_sendsTheAnswerFromHistory() {
    val agent = TestRemoteAgent("my-agent")
    val answer = mockInputAnswer(emptyMap())

    val result =
      agent.testPrepareOutboundEvent(
        contextAnswering(agent, listOf(MOCK_INPUT_CALL), Part(functionResponse = answer))
      )

    // With no text to resume with, the history goes out instead, carrying the function response.
    assertEquals(listOf(Part(functionResponse = answer)), result.content?.parts)
    assertNull(result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_nullResultAnswerToMockInputCall_sendsTheAnswerFromHistory() {
    val agent = TestRemoteAgent("my-agent")
    val answer = mockInputAnswer(mapOf("result" to null))

    val result =
      agent.testPrepareOutboundEvent(
        contextAnswering(agent, listOf(MOCK_INPUT_CALL), Part(functionResponse = answer))
      )

    assertEquals(listOf(Part(functionResponse = answer)), result.content?.parts)
    assertNull(result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_answerToMockAuthCall_isLeftOut() {
    val agent = TestRemoteAgent("my-agent")
    val authCall = FunctionCall(name = "mock_function_call_for_required_user_auth", id = "mock-id")
    val token =
      FunctionResponse(name = "my-auth", response = mapOf("result" to "t0k3n"), id = "mock-id")

    val result =
      agent.testPrepareOutboundEvent(
        contextAnswering(agent, listOf(authCall), Part(functionResponse = token))
      )

    assertEquals(true, result.content?.parts?.isEmpty())
  }

  @Test
  fun prepareOutboundEvent_mockAnswerBesideOtherResponse_staysResume() {
    val agent = TestRemoteAgent("my-agent")
    val calls = listOf(MOCK_INPUT_CALL, FunctionCall(name = "lookup", id = "fc-id"))
    val mockAnswer = mockInputAnswer(mapOf("result" to "Blue"))
    val lookup = FunctionResponse(name = "lookup", response = mapOf("ok" to true), id = "fc-id")
    val context =
      contextAnswering(
        agent,
        calls,
        Part(functionResponse = mockAnswer),
        Part(functionResponse = lookup),
        Part(text = "and more"),
      )

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals(listOf(mockAnswer, lookup), result.content?.parts?.map { it.functionResponse })
  }

  @Test
  fun prepareOutboundEvent_answerWithoutMockCall_keepsText() {
    val agent = TestRemoteAgent("my-agent")
    val lookup = FunctionResponse(name = "lookup", response = mapOf("ok" to true), id = "fc-id")
    val context =
      contextAnswering(
        agent,
        listOf(FunctionCall(name = "lookup", id = "fc-id")),
        Part(functionResponse = lookup),
        Part(text = "and more"),
      )

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals(
      listOf(Part(functionResponse = lookup), Part(text = "and more")),
      result.content?.parts,
    )
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

  /** A session where [agent] made [calls] on a remote task and the user answers with [answer]. */
  private fun contextAnswering(
    agent: TestRemoteAgent,
    calls: List<FunctionCall>,
    vararg answer: Part,
  ): InvocationContext {
    val remoteCalls =
      Event(
        author = agent.name,
        content = Content(role = Role.MODEL, parts = calls.map { Part(functionCall = it) }),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val userAnswer =
      Event(author = Role.USER, content = Content(role = Role.USER, parts = answer.toList()))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(remoteCalls, userAnswer))
    return InvocationContext(
      invocationId = "inv-123",
      agent = agent,
      session = session,
      runConfig = null,
    )
  }
}
