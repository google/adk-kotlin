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

package com.google.adk.kt.runners

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.RequestCredentialArgs
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.auth.SignInRequest
import com.google.adk.kt.auth.signInConfig
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.ResumableEvents.END_OF_AGENT
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.simplifyResumableEvents
import com.google.adk.kt.testing.testApiKeyConfig
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * End-to-end sign-in for a tool through the public Runner: the tool asks for a credential, ADK
 * emits a sign-in request and ends the agent call, and the app answers with [SignInRequest.reply],
 * so ADK stores the credential and re-runs the tool.
 */
@OptIn(ExperimentalAuthApi::class)
class FunctionToolSignInIntegrationTest {

  private var modelCalls = 0

  /** The credential each tool run saw, by tool name, in order. */
  private val seen = mutableListOf<Pair<String, AuthCredential?>>()

  @Test
  fun runAsync_nodePath_twoRequestsAnsweredOneAtATime_rerunsEachTool() = runBlocking {
    val root =
      agent(
        SignInTool("tool_a", CONFIG),
        SignInTool("tool_b", testApiKeyConfig(credentialKey = "other-key")),
        calls = listOf(toolCall("tool_a", "call-a"), toolCall("tool_b", "call-b")),
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootNode = root,
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val (requestA, requestB) = runner.send(userMessage("hi")).signInRequests()
    val unused = runner.send(requestA.reply(API_KEY))

    val second = runner.send(requestB.reply(API_KEY))

    assertEquals(
      listOf(
        AGENT_NAME to toolResponsePart("tool_b", "ok"),
        AGENT_NAME to "done",
        AGENT_NAME to END_OF_AGENT,
      ),
      simplifyResumableEvents(second),
    )
    assertEquals(listOf("tool_a", "tool_b", "tool_a", "tool_b"), seen.map { (tool, _) -> tool })
  }

  @Test
  fun runAsync_rerunToolAsksAgain_emitsNoNewRequest() = runBlocking {
    // Only the model's own tool calls turn a credential request into an event.
    val runner = InMemoryRunner(agent = agent(SignInTool(TOOL_NAME, CONFIG, alwaysAsks = true)))
    val request = runner.send(userMessage("hi")).signInRequests().single()

    val events = runner.send(request.reply(API_KEY))

    assertEquals(emptyList(), events.flatMap { it.credentialCallIds() })
    assertEquals(2, seen.size)
  }

  @Test
  fun runAsync_response_rerunsTheToolAndCallsTheModel() = runBlocking {
    val runner = InMemoryRunner(agent = agent(SignInTool(TOOL_NAME, CONFIG)))
    val request = runner.send(userMessage("hi")).signInRequests().single()

    val events = runner.send(request.reply(API_KEY))

    assertEquals(
      listOf<Pair<String, AuthCredential?>>(TOOL_NAME to null, TOOL_NAME to API_KEY),
      seen,
    )
    assertEquals(2, modelCalls)
    assertEquals(
      listOf(AGENT_NAME to toolResponsePart(TOOL_NAME, "ok"), AGENT_NAME to "done"),
      simplifyEvents(events),
    )
  }

  @Test
  fun runAsync_resumable_pausesAndTheResponseAloneResumes() = runBlocking {
    val runner = resumableRunner(agent(SignInTool(TOOL_NAME, CONFIG)))
    val firstTurn = runner.send(userMessage("hi"))
    val request = firstTurn.signInRequests().single()

    val resumed = runner.send(request.reply(API_KEY))

    assertFalse(AGENT_NAME to END_OF_AGENT in simplifyResumableEvents(firstTurn))
    assertEquals(setOf(firstTurn.first().invocationId), resumed.map { it.invocationId }.toSet())
    assertEquals(
      listOf(
        AGENT_NAME to toolResponsePart(TOOL_NAME, "ok"),
        AGENT_NAME to "done",
        AGENT_NAME to END_OF_AGENT,
      ),
      simplifyResumableEvents(resumed),
    )
  }

  @Test
  fun runAsync_resumable_response_rerunsTheToolOnce() = runBlocking {
    val runner = resumableRunner(agent(SignInTool(TOOL_NAME, CONFIG)))
    val request = runner.send(userMessage("hi")).signInRequests().single()

    val resumed = runner.send(request.reply(API_KEY))

    assertEquals(
      listOf<Pair<String, AuthCredential?>>(TOOL_NAME to null, TOOL_NAME to API_KEY),
      seen,
    )
    assertEquals(
      1,
      resumed.count { event -> event.functionResponses().any { it.name == TOOL_NAME } },
    )
  }

  @Test
  fun runAsync_resumable_twoRequestsAnsweredOneAtATime_dropsTheSecond() = runBlocking {
    // The first answer ends the agent, and a runner on the agent path doesn't resume an ended one.
    val runner =
      resumableRunner(
        agent(
          SignInTool("tool_a", CONFIG),
          SignInTool("tool_b", testApiKeyConfig(credentialKey = "other-key")),
          calls = listOf(toolCall("tool_a", "call-a"), toolCall("tool_b", "call-b")),
        )
      )
    val (requestA, requestB) = runner.send(userMessage("hi")).signInRequests()
    val unused = runner.send(requestA.reply(API_KEY))

    val second = runner.send(requestB.reply(API_KEY))

    assertEquals(emptyList(), second)
    assertEquals(listOf("tool_a", "tool_b", "tool_a"), seen.map { (tool, _) -> tool })
  }

  @Test
  fun runAsync_resumable_twoRequestsAnsweredTogether_rerunsBothThenCallsTheModel() = runBlocking {
    val runner =
      resumableRunner(
        agent(
          SignInTool("tool_a", CONFIG),
          SignInTool("tool_b", testApiKeyConfig(credentialKey = "other-key")),
          calls = listOf(toolCall("tool_a", "call-a"), toolCall("tool_b", "call-b")),
        )
      )
    val requests = runner.send(userMessage("hi")).signInRequests()

    val resumed =
      runner.send(Content(role = Role.USER, parts = requests.flatMap { it.reply(API_KEY).parts }))

    assertEquals(
      listOf(
        AGENT_NAME to listOf(toolResponsePart("tool_a", "ok"), toolResponsePart("tool_b", "ok")),
        AGENT_NAME to "done",
        AGENT_NAME to END_OF_AGENT,
      ),
      simplifyResumableEvents(resumed),
    )
    assertEquals(2, modelCalls)
  }

  @Test
  fun runAsync_sequentialAgent_signInInTheFirstStep_runsTheSecondStep() = runBlocking {
    val model = model(listOf(toolCall(TOOL_NAME, "call-1")))
    val first =
      LlmAgent(name = "first", model = model, tools = listOf(SignInTool(TOOL_NAME, CONFIG)))
    val second = LlmAgent(name = "second", model = model)
    val runner =
      InMemoryRunner(agent = SequentialAgent(name = "root", subAgents = listOf(first, second)))

    val events = runner.send(userMessage("hi"))

    assertEquals("second" to "done", simplifyEvents(events).last())
    assertEquals(2, modelCalls)
  }

  @Test
  fun runAsync_signInAndConfirmationInOneStep_emitsSignInThenConfirmationThenResponses() =
    runBlocking {
      val gated =
        object : FunctionTool("gated", "Needs approval.", requiresConfirmation = true) {
          override fun declaration() = null

          override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any =
            mapOf("status" to "ok")
        }
      val calls = listOf(toolCall(TOOL_NAME, "call-1"), toolCall("gated", "call-2"))
      val runner =
        InMemoryRunner(agent = agent(SignInTool(TOOL_NAME, CONFIG), gated, calls = calls))

      val events = runner.send(userMessage("hi"))

      assertEquals(
        listOf(
          listOf(TOOL_NAME, "gated"),
          listOf(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME),
          listOf(FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME),
          listOf("$TOOL_NAME response", "gated response"),
        ),
        events.map { event ->
          event.functionCalls().map { it.name } +
            event.functionResponses().map { "${it.name} response" }
        },
      )
    }

  @Test
  fun runAsync_stopAtTheRequest_responseRerunsTheTool() = runBlocking {
    // An app may stop reading at the request, so the tool's response event is never stored.
    val runner = InMemoryRunner(agent = agent(SignInTool(TOOL_NAME, CONFIG)))
    val request =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("hi"))
        .first { it.signInRequests().isNotEmpty() }
        .signInRequests()
        .single()

    val events = runner.send(request.reply(API_KEY))

    assertEquals(
      listOf(AGENT_NAME to toolResponsePart(TOOL_NAME, "ok"), AGENT_NAME to "done"),
      simplifyEvents(events),
    )
  }

  @Test
  fun runAsync_toolRequestsCredential_emitsTheRequestBeforeTheResponse() = runBlocking {
    val runner = InMemoryRunner(agent = agent(SignInTool(TOOL_NAME, CONFIG)))

    val events = runner.send(userMessage("hi"))

    val request = RequestCredentialArgs("call-1", CONFIG.signInConfig())
    assertEquals(
      listOf(
        AGENT_NAME to Part(functionCall = FunctionCall(name = TOOL_NAME, args = emptyMap())),
        AGENT_NAME to
          Part(
            functionCall =
              FunctionCall(
                name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                args = request.toMap(),
              )
          ),
        AGENT_NAME to toolResponsePart(TOOL_NAME, "pending"),
      ),
      simplifyEvents(events),
    )
    // Event.signInRequests lists only long-running calls, so ADK must mark each of its own.
    val signInEvent = events.single { it.credentialCallIds().isNotEmpty() }
    assertEquals(signInEvent.credentialCallIds().toSet(), signInEvent.longRunningToolIds)
  }

  @Test
  fun runAsync_toolRequestsCredential_makesNoFurtherModelCall() = runBlocking {
    val runner = InMemoryRunner(agent = agent(SignInTool(TOOL_NAME, CONFIG)))

    val events = runner.send(userMessage("hi"))

    assertEquals(1, modelCalls)
    assertEquals(
      AGENT_NAME to toolResponsePart(TOOL_NAME, "pending"),
      simplifyEvents(events).last(),
    )
  }

  /**
   * A tool that asks for [config]'s credential until it has one, or on every run if [alwaysAsks],
   * recording the credential each run sees in [seen].
   */
  private inner class SignInTool(
    name: String,
    private val config: AuthConfig,
    private val alwaysAsks: Boolean = false,
  ) : FunctionTool(name, "Reads the user's data.") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
      val credential = context.getAuthResponse(config)
      seen += name to credential
      if (credential == null || alwaysAsks) {
        context.requestCredential(config)
        return mapOf("status" to "pending")
      }
      return mapOf("status" to "ok")
    }
  }

  /** A model that makes [calls] on its first call and answers "done" after that, counting calls. */
  private fun model(calls: List<FunctionCall>): DummyModel =
    DummyModel("model") {
      modelCalls++
      flowOf(
        if (modelCalls == 1) modelParallelFunctionCallsResponse(*calls.toTypedArray())
        else LlmResponse(content = modelMessage("done"))
      )
    }

  /** An agent with [tools] whose model makes [calls] first, by default one call to [TOOL_NAME]. */
  private fun agent(
    vararg tools: BaseTool,
    calls: List<FunctionCall> = listOf(toolCall(TOOL_NAME, "call-1")),
  ): LlmAgent = LlmAgent(name = AGENT_NAME, model = model(calls), tools = tools.toList())

  /** An [InMemoryRunner] for [rootAgent] with resumability enabled, so sign-in requests pause. */
  private fun resumableRunner(rootAgent: BaseAgent): InMemoryRunner =
    InMemoryRunner(
      app =
        App(
          appName = "test_app",
          rootAgent = rootAgent,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
    )

  private suspend fun Runner.send(message: Content): List<Event> =
    runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = message).toList()

  private companion object {
    const val AGENT_NAME = "agent"
    const val TOOL_NAME = "read_data"
    const val USER_ID = "u"
    const val SESSION_ID = "s"

    val CONFIG = testApiKeyConfig(credentialKey = "key")

    val API_KEY = AuthCredential.ApiKey(Secret("user-key"))

    fun toolCall(name: String, id: String): FunctionCall =
      FunctionCall(name = name, args = emptyMap(), id = id)

    fun toolResponsePart(name: String, status: String): Part =
      Part(functionResponse = FunctionResponse(name = name, response = mapOf("status" to status)))

    /** Returns the sign-in requests in these events, in order. */
    fun List<Event>.signInRequests(): List<SignInRequest> = flatMap { it.signInRequests() }

    /** Returns the ids of this event's `adk_request_credential` calls, long-running or not. */
    fun Event.credentialCallIds(): List<String> =
      functionCalls()
        .filter { it.name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME }
        .mapNotNull { it.id }
  }
}
