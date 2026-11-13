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

import com.google.adk.kt.a2a.converters.contextId
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
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
        customMetadata = mapOf("a2a:task_id" to "task-123", "a2a:context_id" to "ctx-456"),
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
    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
    assertEquals("ctx-456", result.customMetadata?.get("a2a:context_id"))
  }

  @Test
  fun prepareOutboundEvent_answerCarryingLegacyIds_sendsTheCallsTask() {
    val agent = TestRemoteAgent("my-agent")
    val fc = FunctionCall(name = "my-func", id = "fc-id")
    val call =
      Event(
        author = agent.name,
        content = modelMessage(Part(functionCall = fc)),
        customMetadata = mapOf("a2a:task_id" to "task-123", "a2a:context_id" to "ctx-456"),
      )
    // The runner merges RunConfig.customMetadata into the user's event, so it may hold any key.
    val answer =
      Event(
        author = Role.USER,
        content = userFunctionResponse(name = "my-func", id = "fc-id"),
        customMetadata = mapOf("adk_task_id" to "stale-task", "adk_context_id" to "stale-ctx"),
      )

    val result = agent.testPrepareOutboundEvent(contextWith(agent, listOf(call, answer)))

    assertEquals(
      mapOf(
        "a2a:task_id" to "task-123",
        "a2a:context_id" to "ctx-456",
        "adk_task_id" to "task-123",
        "adk_context_id" to "ctx-456",
      ),
      result.customMetadata,
    )
  }

  @Test
  fun prepareOutboundEvent_contextEditedUnderPythonKey_sendsEditedContext() {
    val agent = TestRemoteAgent("my-agent")
    // A hook or plugin changed a2a:context_id after the event was recorded, which ADK Python
    // allows.
    val edited =
      Event(
        author = agent.name,
        content = modelMessage("Done"),
        customMetadata =
          mapOf(
            "adk_context_id" to "ctx-456",
            "a2a:context_id" to "edited-ctx",
            "a2a:response" to """{"id":"task-123","status":{"state":"TASK_STATE_COMPLETED"}}""",
          ),
      )

    val result = agent.testPrepareOutboundEvent(contextReplyingTo(agent, edited))

    assertEquals("edited-ctx", result.contextId)
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
    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
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
    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
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
    assertNull(result.customMetadata?.get("a2a:task_id"))
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
    assertNull(result.customMetadata?.get("a2a:task_id"))
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
  fun prepareOutboundEvent_textReplyToPausedTask_resumesIt() {
    val agent = TestRemoteAgent("my-agent")
    val context = contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"))

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals(
      mapOf(
        "a2a:task_id" to "task-123",
        "a2a:context_id" to "ctx-456",
        "adk_task_id" to "task-123",
        "adk_context_id" to "ctx-456",
      ),
      result.customMetadata,
    )
    assertEquals(listOf("Blue"), result.content?.parts?.map { it.text })
  }

  @Test
  fun prepareOutboundEvent_textReplyToAuthRequiredTask_resumesIt() {
    val agent = TestRemoteAgent("my-agent")
    val context = contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_AUTH_REQUIRED"))

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyToTaskPausedInLegacySession_resumesIt() {
    val agent = TestRemoteAgent("my-agent")
    val paused = remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED")
    // Earlier versions recorded the ids under adk_task_id and adk_context_id.
    val legacyPaused =
      paused.copy(
        customMetadata =
          mapOf(
            "adk_task_id" to "task-123",
            "adk_context_id" to "ctx-456",
            "a2a:response" to paused.customMetadata?.get("a2a:response"),
          )
      )

    val result = agent.testPrepareOutboundEvent(contextReplyingTo(agent, legacyPaused))

    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
    assertEquals("ctx-456", result.customMetadata?.get("a2a:context_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyToTaskPausedOverA2a03_resumesIt() {
    val agent = TestRemoteAgent("my-agent")
    val context = contextReplyingTo(agent, remoteResponse(agent, "input-required"))

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_pausedTaskInOtherContext_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val laterEvent =
      Event(author = agent.name, customMetadata = mapOf("a2a:context_id" to "other-context"))
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), laterEvent)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterErrorWithoutRequest_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    // Earlier versions stored an unfinished task's error without the request.
    val error =
      Event(
        author = agent.name,
        errorMessage = "A2A response ended before the remote task reached a terminal state",
        customMetadata = mapOf("a2a:context_id" to "ctx-456"),
      )
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), error)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
    assertEquals("ctx-456", result.customMetadata?.get("a2a:context_id"))
  }

  @Test
  fun prepareOutboundEvent_otherAgentEventsAfterPause_stillResumesTask() {
    val agent = TestRemoteAgent("my-agent")
    val otherAgentsError = Event(author = "other-agent", errorMessage = "failed")
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), otherAgentsError)

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyToCompletedTask_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val context = contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_COMPLETED"))

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
    assertEquals("ctx-456", result.customMetadata?.get("a2a:context_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyToTaskThatIsNotPaused_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val states =
      listOf(
        "TASK_STATE_SUBMITTED",
        "TASK_STATE_WORKING",
        "TASK_STATE_COMPLETED",
        "TASK_STATE_FAILED",
        "TASK_STATE_CANCELED",
        "TASK_STATE_REJECTED",
        "TASK_STATE_UNSPECIFIED",
      )
    for (state in states) {
      val context = contextReplyingTo(agent, remoteResponse(agent, state))

      val result = agent.testPrepareOutboundEvent(context)

      assertNull(result.customMetadata?.get("a2a:task_id"), state)
    }
  }

  @Test
  fun prepareOutboundEvent_attachmentReplyToPausedTask_resumesIt() {
    val agent = TestRemoteAgent("my-agent")
    val image = Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3)))
    val reply =
      Event(author = Role.USER, content = Content(role = Role.USER, parts = listOf(image)))
    val context =
      contextWith(agent, listOf(remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), reply))

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterPausedTaskCompleted_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val context =
      contextReplyingTo(
        agent,
        remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"),
        remoteResponse(agent, "TASK_STATE_COMPLETED"),
      )

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterLocalAgentEvent_resumesPausedTask() {
    val agent = TestRemoteAgent("my-agent")
    val callbackEvent = Event(author = agent.name, content = modelMessage("Noted."))
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), callbackEvent)

    val result = agent.testPrepareOutboundEvent(context)

    assertEquals("task-123", result.customMetadata?.get("a2a:task_id"))
    assertEquals("ctx-456", result.customMetadata?.get("a2a:context_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterUnrecordedResponse_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val unrecorded =
      Event(
        author = agent.name,
        customMetadata =
          mapOf("a2a:context_id" to "ctx-456", BaseRemoteA2AAgent.RESPONSE_ERROR to "failed"),
      )
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), unrecorded)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterResponseThatIsNotText_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val unreadable =
      Event(
        author = agent.name,
        customMetadata =
          mapOf("a2a:context_id" to "ctx-456", "a2a:response" to mapOf("id" to "task-123")),
      )
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), unreadable)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterMalformedResponse_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val malformed =
      Event(
        author = agent.name,
        customMetadata = mapOf("a2a:context_id" to "ctx-456", "a2a:response" to "{"),
      )
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), malformed)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
    assertEquals(listOf("Blue"), result.content?.parts?.map { it.text })
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterFailedRequest_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val failedRequest =
      Event(
        author = agent.name,
        errorMessage = "A2A request failed (IOException)",
        customMetadata = mapOf("a2a:context_id" to "ctx-456", "a2a:request" to "{}"),
      )
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), failedRequest)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyAfterFailedUnserializableRequest_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val failedRequest =
      Event(
        author = agent.name,
        errorMessage = "A2A request failed (IOException)",
        customMetadata = mapOf("a2a:request_serialization_error" to "unserializable"),
      )
    val context =
      contextReplyingTo(agent, remoteResponse(agent, "TASK_STATE_INPUT_REQUIRED"), failedRequest)

    val result = agent.testPrepareOutboundEvent(context)

    assertNull(result.customMetadata?.get("a2a:task_id"))
  }

  @Test
  fun prepareOutboundEvent_textReplyToMessageResponse_startsNewTask() {
    val agent = TestRemoteAgent("my-agent")
    val messageResponse =
      Event(
        author = agent.name,
        content = modelMessage("Hi"),
        customMetadata =
          mapOf("a2a:context_id" to "ctx-456", "a2a:response" to """{"messageId":"m1"}"""),
      )

    val result = agent.testPrepareOutboundEvent(contextReplyingTo(agent, messageResponse))

    assertNull(result.customMetadata?.get("a2a:task_id"))
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
        customMetadata = mapOf("a2a:context_id" to "parent-context-123"),
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
    assertEquals("parent-context-123", result.customMetadata?.get("a2a:context_id"))
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

  /** An event where [agent] relays its remote agent's response leaving task-123 in [state]. */
  private fun remoteResponse(agent: TestRemoteAgent, state: String) =
    Event(
      author = agent.name,
      content = modelMessage("Which color?"),
      customMetadata =
        mapOf(
          "a2a:task_id" to "task-123",
          "a2a:context_id" to "ctx-456",
          "a2a:response" to
            """{"id":"task-123","contextId":"ctx-456","status":{"state":"$state"}}""",
        ),
    )

  /** A context whose session holds [events], then the user's text reply "Blue". */
  private fun contextReplyingTo(agent: TestRemoteAgent, vararg events: Event) =
    contextWith(agent, events.toList() + Event(author = Role.USER, content = userMessage("Blue")))

  private fun contextWith(agent: TestRemoteAgent, events: List<Event>): InvocationContext {
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(events)
    return InvocationContext(
      invocationId = "inv-123",
      agent = agent,
      session = session,
      runConfig = null,
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
        customMetadata = mapOf("a2a:task_id" to "task-123", "a2a:context_id" to "ctx-456"),
      )
    val userAnswer =
      Event(author = Role.USER, content = Content(role = Role.USER, parts = answer.toList()))
    return contextWith(agent, listOf(remoteCalls, userAnswer))
  }
}
