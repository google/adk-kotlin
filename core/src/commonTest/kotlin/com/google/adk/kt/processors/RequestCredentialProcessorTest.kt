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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.RequestCredentialArgs
import com.google.adk.kt.auth.signInConfig
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.signInReplyStateKey
import com.google.adk.kt.testing.testApiKeyConfig
import com.google.adk.kt.testing.testOpenIdConnectConfig
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * How credential requests are built, how responses are matched to them, and which tool calls
 * re-run.
 */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class RequestCredentialProcessorTest {

  private val runs = mutableListOf<String>()

  /** The events the processor emitted, in order. */
  private val emitted = mutableListOf<Event>()

  @Test
  fun generateRequestCredentialEvent_noRequestedAuthConfigs_returnsNull() {
    assertNull(generateRequestCredentialEvent(newContext(), toolResponseEvent()))
  }

  @Test
  fun generateRequestCredentialEvent_oneRequest_passesTheRequestArgs() {
    val response = toolResponseEvent("call-1" to REQUEST)

    val event = checkNotNull(generateRequestCredentialEvent(newContext(), response))

    val call = event.functionCalls().single()
    assertEquals(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, call.name)
    assertEquals(RequestCredentialArgs("call-1", REQUEST).toMap(), call.args)
  }

  @Test
  fun generateRequestCredentialEvent_requestWithoutResponse_isAskedForAfterTheAnsweredOnes() {
    // Every requested credential is asked for; both orders, as the map keeps none.
    val other = testApiKeyConfig(credentialKey = "other")
    val callOrders = listOf("call-1" to "call-2", "call-2" to "call-1")

    for ((answered, unanswered) in callOrders) {
      val response =
        toolResponseEvent(answered to REQUEST).apply {
          actions.requestedAuthConfigs[unanswered] = other
        }

      val event = checkNotNull(generateRequestCredentialEvent(newContext(), response))

      assertEquals(
        listOf(answered, unanswered),
        event.functionCalls().map { it.args["functionCallId"] },
      )
    }
  }

  @Test
  fun generateRequestCredentialEvent_responseWithStateDelta_carriesOnlyTheRequestedConfigs() {
    // Sharing the response's actions would apply its state delta twice.
    val response = toolResponseEvent("call-1" to REQUEST).apply { actions.stateDelta["k"] = "v" }

    val event = checkNotNull(generateRequestCredentialEvent(newContext(), response))

    assertEquals(EventActions().apply { requestedAuthConfigs["call-1"] = REQUEST }, event.actions)
  }

  @Test
  fun generateRequestCredentialEvent_sameCredentialKey_makesOneCallWithTheFirstConfig() {
    // A copy keeps the credential key; both call orders, since the map itself has no order.
    val sameKey = REQUEST.copy(rawAuthCredential = null)
    val callOrders = listOf("call-1" to "call-2", "call-2" to "call-1")

    for ((first, second) in callOrders) {
      val response = toolResponseEvent(first to REQUEST, second to sameKey)

      val event = checkNotNull(generateRequestCredentialEvent(newContext(), response))

      assertEquals(
        listOf(RequestCredentialArgs(first, REQUEST).toMap()),
        event.functionCalls().map { it.args },
      )
    }
  }

  @Test
  fun generateRequestCredentialEvent_twoCredentialKeys_makesLongRunningCallsInCallOrder() {
    // Both call orders, since one of them always matches the map's own order.
    val other = testApiKeyConfig(credentialKey = "other")
    val callOrders = listOf("call-1" to "call-2", "call-2" to "call-1")

    for ((first, second) in callOrders) {
      val response = toolResponseEvent(first to REQUEST, second to other)

      val event = checkNotNull(generateRequestCredentialEvent(newContext(), response))

      val calls = event.functionCalls()
      assertEquals(listOf(first, second), calls.map { it.args["functionCallId"] })
      assertEquals(calls.mapNotNull { it.id }.toSet(), event.longRunningToolIds)
      assertEquals(2, event.longRunningToolIds.size)
    }
  }

  @Test
  fun generateRequestCredentialEvent_userResponseOnBranch_isTheAgentsWithTheUserRole() {
    val context = newContext(branch = "parent.test")
    val response =
      toolResponseEvent("call-1" to REQUEST).run { copy(content = content?.copy(role = Role.USER)) }

    val event = checkNotNull(generateRequestCredentialEvent(context, response))

    assertEquals(AGENT_NAME, event.author)
    assertEquals("parent.test", event.branch)
    assertEquals(context.invocationId, event.invocationId)
    assertEquals(Role.USER, event.content?.role)
  }

  @Test
  fun process_callFromAnotherAuthor_storesButDoesNotRerunIt() = runBlocking {
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1"), author = "peer"),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertNotNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_callInSeveralEvents_rerunsOnlyTheLatest() = runBlocking {
    val context = newContext(recordingTool("older_tool"), recordingTool("newer_tool"))
    context.session.events.addAll(
      listOf(
        agentEvent(callPart("older_tool", "call-1")),
        agentEvent(callPart("newer_tool", "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertEquals(listOf("newer_tool"), runs)
  }

  @Test
  fun process_callWithoutId_isNotRerun() = runBlocking {
    val context = newContext(recordingTool("tool_a"), recordingTool("tool_b"))
    context.session.events.addAll(
      listOf(
        agentEvent(
          callPart("tool_a", "call-1"),
          Part(functionCall = FunctionCall(name = "tool_b", args = emptyMap(), id = null)),
        ),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertEquals(listOf("tool_a"), runs)
  }

  @Test
  fun process_credentialRequestFromAnotherAuthor_ignoresTheResponse() = runBlocking {
    // Only this agent's own request counts, so another author can't choose the key.
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1"), author = "peer"),
        toolResponseEvent("call-1" to REQUEST),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_credentialRequestNoToolAskedFor_ignoresTheResponse() = runBlocking {
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_credentialRequestWithAnotherConfig_ignoresTheResponse() = runBlocking {
    // Same key, but not the config the tool asked for, so no scheme can store a code-only answer.
    val forged = AuthConfig(AuthScheme.Http.bearer(), credentialKey = REQUEST.credentialKey)
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1", forged)),
        toolResponseEvent("call-1" to REQUEST),
        credentialResponse(mapOf("authType" to "oauth2", "oauth2" to mapOf("authCode" to "code"))),
      )
    )

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_eventWithoutContentAfterTheResponse_storesAndRerunsTheTool() = runBlocking {
    // Workflow agents emit events without content, which can come after the response.
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS))
    context.session.events.add(Event(author = "workflow"))

    context.process()

    assertNotNull(context.stored())
    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_extraTool_rerunsIt() = runBlocking {
    val context = newContext(recordingTool("other_tool"))
    context.extraTools[TOOL_NAME] = recordingTool(TOOL_NAME)
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS))

    context.process()

    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_failedStore_rerunsOnlyTheRequestingCall() = runBlocking {
    val context = newContext(recordingTool("tool_a"), recordingTool("tool_b"))
    context.session.events.addAll(
      listOf(
        agentEvent(callPart("tool_a", "call-1"), callPart("tool_b", "call-2")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST, "call-2" to REQUEST),
        credentialResponse(
          mapOf("authType" to "openIdConnect", "oauth2" to mapOf("authCode" to "code"))
        ),
      )
    )

    context.process()

    assertEquals(listOf("tool_a"), runs)
  }

  @Test
  fun process_malformedResponse_storesNothingAndRerunsTheTool() = runBlocking {
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(mapOf("foo" to 1)))

    context.process()

    assertNull(context.stored())
    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_olderRequestMapWithTheSameKey_addsNoCalls() = runBlocking {
    val context = newContext(recordingTool("tool_a"), recordingTool("tool_b"))
    context.session.events.addAll(
      listOf(
        toolResponseEvent("call-0" to REQUEST),
        agentEvent(callPart("tool_a", "call-1"), callPart("tool_b", "call-0")),
        toolResponseEvent("call-1" to REQUEST),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertEquals(listOf("tool_a"), runs)
  }

  @Test
  fun process_peerRequestMap_addsNoCalls() = runBlocking {
    val context = newContext(recordingTool("tool_a"), recordingTool("tool_b"))
    context.session.events.addAll(
      listOf(
        agentEvent(callPart("tool_a", "call-1"), callPart("tool_b", "call-2")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST),
        toolResponseEvent("call-1" to REQUEST, "call-2" to REQUEST, author = "peer"),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertEquals(listOf("tool_a"), runs)
  }

  @Test
  fun process_requestedConfigWithSubSecondExpiry_storesAndRerunsTheTool() = runBlocking {
    // The request's args went through JSON, which keeps whole seconds of an expiry only.
    val raw =
      AuthCredential.OAuth2(
        variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
        clientId = "cid",
        redirectUri = "https://app/cb",
        expiresAt = Instant.fromEpochMilliseconds(1_500),
      )
    val request = AuthConfig(TOOL_CONFIG.authScheme, raw).signInConfig()
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1", request)),
        toolResponseEvent("call-1" to request),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertNotNull(context.session.state[request.signInReplyStateKey])
    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_responseFromAnotherAuthor_ignoresIt() = runBlocking {
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS, author = "peer"))

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_responseOnAnotherBranch_isIgnored() = runBlocking {
    val context = newContext(branch = "root.test")
    context.session.events.addAll(credentialRequestEvents().map { it.copy(branch = "root.test") })
    context.session.events.add(credentialResponse(TOKENS).copy(branch = "root.other"))

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_responseWithoutId_isIgnored() = runBlocking {
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS, id = null))

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_runTwice_rerunsTheToolOnce() = runBlocking {
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS))

    context.process()
    context.session.events.addAll(emitted)
    context.process()

    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_sameKeyInTheRequestMap_rerunsEveryCallWithThatKey() = runBlocking {
    val other = testApiKeyConfig(credentialKey = "other")
    val context =
      newContext(recordingTool("tool_a"), recordingTool("tool_b"), recordingTool("tool_c"))
    context.session.events.addAll(
      listOf(
        agentEvent(
          callPart("tool_a", "call-1"),
          callPart("tool_b", "call-2"),
          callPart("tool_c", "call-3"),
        ),
        agentEvent(
          credentialRequestPart("req-1", "call-1"),
          credentialRequestPart("req-3", "call-3", other),
        ),
        toolResponseEvent("call-1" to REQUEST, "call-2" to REQUEST, "call-3" to other),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertEquals(listOf("tool_a", "tool_b"), runs.sorted())
  }

  @Test
  fun process_toolRequestForAnotherKey_ignoresTheResponse() = runBlocking {
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to testApiKeyConfig(credentialKey = "other")),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_toolRequestFromAnotherAuthor_ignoresTheResponse() = runBlocking {
    val context = newContext()
    context.session.events.addAll(
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST, author = "peer"),
        credentialResponse(TOKENS),
      )
    )

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @Test
  fun process_toolsetTool_rerunsIt() = runBlocking {
    val toolset =
      object : Toolset {
        override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
          listOf(recordingTool(TOOL_NAME))
      }
    val agent =
      LlmAgent(name = AGENT_NAME, model = DummyModel("gemini"), toolsets = listOf(toolset))
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS))

    context.process()

    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_twoResponses_storesBothAndRerunsBothTools() = runBlocking {
    val apiKeyRequest = testApiKeyConfig(credentialKey = "other")
    val context = newContext(recordingTool("tool_a"), recordingTool("tool_b"))
    context.session.events.addAll(
      listOf(
        agentEvent(callPart("tool_a", "call-1"), callPart("tool_b", "call-2")),
        agentEvent(
          credentialRequestPart("req-1", "call-1"),
          credentialRequestPart("req-2", "call-2", apiKeyRequest),
        ),
        toolResponseEvent("call-1" to REQUEST, "call-2" to apiKeyRequest),
      )
    )
    val parts =
      listOf(
          credentialResponse(TOKENS),
          credentialResponse(mapOf("authType" to "apiKey", "apiKey" to "k"), id = "req-2"),
        )
        .flatMap { it.content?.parts.orEmpty() }
    context.session.events.add(
      Event(author = Role.USER, content = Content(role = Role.USER, parts = parts))
    )

    context.process()

    assertNotNull(context.stored())
    assertNotNull(context.session.state[apiKeyRequest.signInReplyStateKey])
    assertEquals(listOf("tool_a", "tool_b"), runs.sorted())
  }

  @Test
  fun process_unknownResponseIdBeforeAKnownOne_handlesTheKnownOne() = runBlocking {
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    val parts =
      listOf(credentialResponse(TOKENS, id = "req-unknown"), credentialResponse(TOKENS)).flatMap {
        it.content?.parts.orEmpty()
      }
    context.session.events.add(
      Event(author = Role.USER, content = Content(role = Role.USER, parts = parts))
    )

    context.process()

    assertNotNull(context.stored())
    assertEquals(listOf(TOOL_NAME), runs)
  }

  @Test
  fun process_unknownResponseId_storesAndRerunsNothing() = runBlocking {
    val context = newContext()
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS, id = "req-unknown"))

    context.process()

    assertNull(context.stored())
    assertEquals(emptyList(), runs)
    assertEquals(emptyList(), emitted)
  }

  @OptIn(SecretAccess::class)
  @Test
  fun process_validResponse_storesTheCredentialAndRerunsTheTool() = runBlocking {
    var credentialSeenByTool: AuthCredential? = null
    val tool =
      DummyTool(TOOL_NAME) { toolContext, _ ->
        credentialSeenByTool = toolContext.getAuthResponse(TOOL_CONFIG)
        mapOf("status" to "ok")
      }
    val context = newContext(tool)
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS))

    context.process()

    val seen = assertIs<AuthCredential.OAuth2>(credentialSeenByTool)
    assertEquals("at", seen.accessToken?.reveal())
    assertEquals("sec", seen.clientSecret?.reveal())
    val event = emitted.single()
    assertEquals(AGENT_NAME, event.author)
    assertEquals(
      listOf(FunctionResponse(name = TOOL_NAME, id = "call-1", response = mapOf("status" to "ok"))),
      event.functionResponses(),
    )
  }

  @Test
  fun runAsync_credentialResponse_storesTheCredentialAndRerunsTheTool() = runBlocking {
    // The other tests call the processor directly; this one pins that LlmAgent runs it.
    val answer =
      LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "ok"))))
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model = DummyModel.createSequential("gemini", listOf(answer)),
        tools = listOf(recordingTool(TOOL_NAME)),
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)
    context.session.events.addAll(credentialRequestEvents())
    context.session.events.add(credentialResponse(TOKENS))

    val events = agent.runAsync(context).toList()

    assertNotNull(context.stored())
    assertEquals(listOf(TOOL_NAME), runs)
    assertEquals(listOf("call-1"), events.flatMap { it.functionResponses() }.map { it.id })
  }

  /** Returns a context for an agent with [tools], by default one tool named [TOOL_NAME]. */
  private fun newContext(vararg tools: BaseTool, branch: String? = null): InvocationContext {
    val agentTools = tools.toList().ifEmpty { listOf(recordingTool(TOOL_NAME)) }
    val agent = LlmAgent(name = AGENT_NAME, model = DummyModel("gemini"), tools = agentTools)
    return InvocationContext(
      session = testSession(),
      runConfig = null,
      agent = agent,
      branch = branch,
    )
  }

  /** Returns a tool named [name] that adds its name to [runs] each time it runs. */
  private fun recordingTool(name: String): BaseTool =
    DummyTool(name) { _, _ ->
      runs.add(name)
      mapOf("status" to "ok")
    }

  /** Runs the processor, adding the events it emits to [emitted]. */
  private suspend fun InvocationContext.process() {
    val unused = RequestCredentialProcessor().process(this, LlmRequest()) { emitted.add(it) }
  }

  /** Returns the credential stored as [REQUEST]'s sign-in reply, if any. */
  private fun InvocationContext.stored(): Any? = session.state[REQUEST.signInReplyStateKey]

  private companion object {
    const val AGENT_NAME = "test"
    const val TOOL_NAME = "secure_tool"

    /** The tool's own config, whose client secret [REQUEST] leaves out. */
    val TOOL_CONFIG = testOpenIdConnectConfig()

    /** The credential request ADK records for [TOOL_CONFIG]. */
    val REQUEST = TOOL_CONFIG.signInConfig()

    val TOKENS =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to mapOf("accessToken" to "at", "refreshToken" to "rt"),
      )

    fun agentEvent(
      vararg parts: Part,
      author: String = AGENT_NAME,
      actions: EventActions = EventActions(),
    ): Event =
      Event(
        author = author,
        content = Content(role = Role.MODEL, parts = parts.toList()),
        actions = actions,
      )

    fun callPart(name: String, id: String): Part =
      Part(functionCall = FunctionCall(name = name, args = emptyMap(), id = id))

    /**
     * The tools' response event for the calls in [requested], each asking for its credential, as
     * `Context.requestCredential` records it.
     */
    fun toolResponseEvent(
      vararg requested: Pair<String, AuthConfig>,
      author: String = AGENT_NAME,
    ): Event =
      agentEvent(
        *requested
          .map { (callId, _) ->
            Part(
              functionResponse =
                FunctionResponse(
                  name = TOOL_NAME,
                  id = callId,
                  response = mapOf("status" to "pending"),
                )
            )
          }
          .toTypedArray(),
        author = author,
        actions = EventActions().apply { requestedAuthConfigs.putAll(requested) },
      )

    fun credentialRequestPart(
      id: String,
      functionCallId: String,
      config: AuthConfig = REQUEST,
    ): Part =
      Part(
        functionCall =
          FunctionCall(
            name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
            args = RequestCredentialArgs(functionCallId, config).toMap(),
            id = id,
          )
      )

    /** The events of a tool call that asked for [REQUEST]'s credential: call, request, response. */
    fun credentialRequestEvents(): List<Event> =
      listOf(
        agentEvent(callPart(TOOL_NAME, "call-1")),
        agentEvent(credentialRequestPart("req-1", "call-1")),
        toolResponseEvent("call-1" to REQUEST),
      )

    /** The app's response to credential request [id]. */
    fun credentialResponse(
      response: Map<String, Any?>,
      id: String? = "req-1",
      author: String = Role.USER,
    ): Event =
      Event(
        author = author,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                      id = id,
                      response = response,
                    )
                )
              ),
          ),
      )
  }
}
