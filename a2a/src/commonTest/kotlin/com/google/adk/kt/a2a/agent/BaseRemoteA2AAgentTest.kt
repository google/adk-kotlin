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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class BaseRemoteA2AAgentTest {

  private class TestRemoteAgent(name: String) : BaseRemoteA2AAgent(name = name) {
    override val isStreamingEnabled: Boolean = true

    var remoteWasCalled = false
      private set

    var lastOutboundEvent: Event? = null
      private set

    override fun createA2aCallbackFlow(
      context: InvocationContext,
      outboundEvent: Event,
    ): Flow<Event> {
      remoteWasCalled = true
      lastOutboundEvent = outboundEvent
      return emptyFlow()
    }

    fun testRunAsyncImpl(context: InvocationContext): Flow<Event> = runAsyncImpl(context)

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
        content = Content(role = Role.USER, parts = listOf(Part(functionCall = fc))),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val fr = FunctionResponse(name = "my-func", id = "fc-id")
    val userResponse =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(functionResponse = fr))),
      )

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
    val event1 =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(text = "hi"))),
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
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "reply"))),
        customMetadata = mapOf("adk_context_id" to "parent-context-123"),
      )
    val nextUserEvent =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(Part(text = "how are you?"))),
      )

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
    val event =
      Event(
        author = "my-agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "reply"))),
      )
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

  /** Builds a session that is resuming an interrupted task with [responseParts]. */
  private fun resumingSession(responseParts: List<Part>): Session {
    val userCall =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts = listOf(Part(functionCall = FunctionCall(name = "my-func", id = "fc-id"))),
          ),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val userResponse =
      Event(author = Role.USER, content = Content(role = Role.USER, parts = responseParts))
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(userCall, userResponse))
    return session
  }

  private fun outboundFor(responseParts: List<Part>): Event {
    val agent = TestRemoteAgent("my-agent")
    return agent.testPrepareOutboundEvent(
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = resumingSession(responseParts),
        runConfig = null,
      )
    )
  }

  @Test
  fun prepareOutboundEvent_credentialRequestResponse_isNotForwarded() {
    val credential =
      Part(
        functionResponse =
          FunctionResponse(
            name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
            id = "fc-id",
            response = mapOf("access_token" to "secret-token"),
          )
      )

    val result = outboundFor(listOf(credential))

    // The credential never goes out; the emptied resume falls back to the history rebuild, so the
    // outbound message carries no task id.
    assertEquals(false, result.content?.parts?.any { it.functionResponse != null })
    assertEquals(null, result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_authConfigShapedResponse_isNotForwarded() {
    val authConfig =
      Part(
        functionResponse =
          FunctionResponse(
            name = "my-func",
            id = "fc-id",
            response = mapOf("exchangedAuthCredential" to mapOf("token" to "secret-token")),
          )
      )

    val result = outboundFor(listOf(authConfig))

    assertEquals(false, result.content?.parts?.any { it.functionResponse != null })
    assertEquals(null, result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_snakeCaseAuthConfigResponse_isNotForwarded() {
    val authConfig =
      Part(
        functionResponse =
          FunctionResponse(
            name = "my-func",
            id = "fc-id",
            response = mapOf("raw_auth_credential" to "secret-token"),
          )
      )

    val result = outboundFor(listOf(authConfig))

    assertEquals(false, result.content?.parts?.any { it.functionResponse != null })
    assertEquals(null, result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_ordinaryResponse_isForwardedUnchanged() {
    val ordinary =
      Part(
        functionResponse =
          FunctionResponse(name = "my-func", id = "fc-id", response = mapOf("result" to "42"))
      )

    val result = outboundFor(listOf(ordinary))

    assertEquals(listOf(ordinary), result.content?.parts)
    assertEquals("task-123", result.customMetadata?.get("adk_task_id"))
  }

  @Test
  fun prepareOutboundEvent_mixedParts_dropsOnlyTheCredential() {
    val ordinary =
      Part(
        functionResponse =
          FunctionResponse(name = "my-func", id = "fc-id", response = mapOf("result" to "42"))
      )
    val credential =
      Part(
        functionResponse =
          FunctionResponse(
            name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
            id = "fc-id",
            response = mapOf("access_token" to "secret-token"),
          )
      )

    val result = outboundFor(listOf(ordinary, credential))

    assertEquals(listOf(ordinary), result.content?.parts)
  }

  @Test
  fun runAsyncImpl_credentialOnlyResume_fallsBackToHistoryWithoutATaskId() {
    val agent = TestRemoteAgent("my-agent")
    val credential =
      Part(
        functionResponse =
          FunctionResponse(
            name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
            id = "fc-id",
            response = mapOf("access_token" to "secret-token"),
          )
      )
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = resumingSession(listOf(credential)),
        runConfig = null,
      )

    val unusedFlow = agent.testRunAsyncImpl(context)

    // Dropping the credential empties the resume, so it rebuilds from history instead: the SDK
    // rejects a Message with no parts, so a bodiless resume is not an option.
    assertEquals(true, agent.remoteWasCalled)
    val outbound = agent.lastOutboundEvent
    assertEquals(null, outbound?.customMetadata?.get("adk_task_id"))
    assertEquals(true, outbound?.content?.parts?.isNotEmpty())
    assertEquals(
      false,
      outbound?.content?.parts?.any {
        it.functionResponse != null || it.text?.contains("secret-token") == true
      },
    )
  }

  @Test
  fun runAsyncImpl_noPartsAndNoTask_skipsTheRemote() {
    val agent = TestRemoteAgent("my-agent")
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    val context =
      InvocationContext(
        invocationId = "inv-123",
        agent = agent,
        session = session,
        runConfig = null,
      )

    val unusedFlow = agent.testRunAsyncImpl(context)

    assertEquals(false, agent.remoteWasCalled)
  }

  @Test
  fun prepareOutboundEvent_credentialOutsideAResume_isNotForwarded() {
    val agent = TestRemoteAgent("my-agent")
    // No matching function call, so this is the history-rebuild path, not a resume.
    val orphanCredential =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(text = "please continue"),
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                      response = mapOf("access_token" to "secret-token"),
                    )
                ),
              ),
          ),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.add(orphanCredential)

    val result =
      agent.testPrepareOutboundEvent(
        InvocationContext(
          invocationId = "inv-123",
          agent = agent,
          session = session,
          runConfig = null,
        )
      )

    assertEquals(listOf(Part(text = "please continue")), result.content?.parts)
  }

  @Test
  fun prepareOutboundEvent_credentialFromAnotherAgent_isNotLeakedAsText() {
    val agent = TestRemoteAgent("my-agent")
    // A foreign author is rephrased into text by presentAsUserMessage; the drop must happen first.
    val foreignCredential =
      Event(
        author = "some-other-agent",
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                      response = mapOf("access_token" to "secret-token"),
                    )
                )
              ),
          ),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.add(foreignCredential)

    val result =
      agent.testPrepareOutboundEvent(
        InvocationContext(
          invocationId = "inv-123",
          agent = agent,
          session = session,
          runConfig = null,
        )
      )

    assertEquals(false, result.content?.parts?.any { it.text?.contains("secret-token") == true })
  }

  @Test
  fun prepareOutboundEvent_authConfigWrappedInResult_isNotForwarded() {
    val wrapped =
      Part(
        functionResponse =
          FunctionResponse(
            name = "my-func",
            id = "fc-id",
            response = mapOf("result" to mapOf("authScheme" to "oauth2", "token" to "secret")),
          )
      )

    val result = outboundFor(listOf(wrapped, Part(text = "carry on")))

    assertEquals(listOf(Part(text = "carry on")), result.content?.parts)
  }

  @Test
  fun runAsyncImpl_credentialOnlyResumeOfAgentAuthoredCall_skipsTheRemote() {
    val agent = TestRemoteAgent("my-agent")
    // Production authors the auth-required call event as the agent, so the history rebuild drops
    // everything up to it and the sanitized resume leaves nothing to send.
    val agentCall =
      Event(
        author = "my-agent",
        content =
          Content(
            role = Role.USER,
            parts = listOf(Part(functionCall = FunctionCall(name = "my-func", id = "fc-id"))),
          ),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val credentialResponse =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                      id = "fc-id",
                      response = mapOf("access_token" to "secret-token"),
                    )
                )
              ),
          ),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(agentCall, credentialResponse))

    val unusedFlow =
      agent.testRunAsyncImpl(
        InvocationContext(
          invocationId = "inv-123",
          agent = agent,
          session = session,
          runConfig = null,
        )
      )

    // Nothing left to send, so no zero-part Message ever reaches the SDK.
    assertEquals(false, agent.remoteWasCalled)
  }

  @Test
  fun prepareOutboundEvent_credentialResponseUnderAnInnocentName_isNotForwarded() {
    val agent = TestRemoteAgent("my-agent")
    // The call was adk_request_credential; the response names itself something else and carries a
    // bare token, so only the call-side name identifies it.
    val credentialCall =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionCall =
                    FunctionCall(name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, id = "fc-id")
                )
              ),
          ),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val mislabelled =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(text = "here you go"),
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = "totally_innocent",
                      id = "fc-id",
                      response = mapOf("access_token" to "secret-token"),
                    )
                ),
              ),
          ),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(credentialCall, mislabelled))

    val result =
      agent.testPrepareOutboundEvent(
        InvocationContext(
          invocationId = "inv-123",
          agent = agent,
          session = session,
          runConfig = null,
        )
      )

    assertEquals(listOf(Part(text = "here you go")), result.content?.parts)
  }

  @Test
  fun prepareOutboundEvent_idLessCredentialCall_dropsTheIdLessResponse() {
    val agent = TestRemoteAgent("my-agent")
    // An id-less credential call pollutes the id-less bucket, so every id-less response in the
    // event is treated as credential-bearing.
    val calls =
      Event(
        author = Role.USER,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(functionCall = FunctionCall(name = "my-func", id = "fc-id")),
                Part(
                  functionCall =
                    FunctionCall(name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, id = null)
                ),
              ),
          ),
        customMetadata = mapOf("adk_task_id" to "task-123", "adk_context_id" to "ctx-456"),
      )
    val ordinary =
      Part(
        functionResponse =
          FunctionResponse(name = "my-func", id = "fc-id", response = mapOf("result" to "42"))
      )
    val idLessCredential =
      Part(
        functionResponse =
          FunctionResponse(
            name = "innocent",
            id = null,
            response = mapOf("access_token" to "secret-token"),
          )
      )
    val responses =
      Event(
        author = Role.USER,
        content = Content(role = Role.USER, parts = listOf(ordinary, idLessCredential)),
      )
    val session = Session(key = SessionKey(appName = "demo", userId = "user", id = "session-1"))
    session.events.addAll(listOf(calls, responses))

    val result =
      agent.testPrepareOutboundEvent(
        InvocationContext(
          invocationId = "inv-123",
          agent = agent,
          session = session,
          runConfig = null,
        )
      )

    assertEquals(listOf(ordinary), result.content?.parts)
  }
}
