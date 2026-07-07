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

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class AgentToolTest {

  @Test
  fun declaration_withInputSchema_buildsCorrectParameters() {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("query" to Schema(type = Type.STRING)),
        required = listOf("query"),
      )
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("inner-agent", declaration.name)
    assertEquals(inputSchema, declaration.parameters)
  }

  @Test
  fun declaration_withoutInputSchema_fallsBackToRequest() {
    val agent = LlmAgent(name = "inner-agent", model = DummyModel("test"))
    val tool = AgentTool(agent)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("inner-agent", declaration.name)
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(1, parameters.properties?.size)
    assertEquals(Type.STRING, parameters.properties?.get("request")?.type)
  }

  @Test
  fun run_executesInnerAgent_returnsResult() = runTest {
    val responseContent = modelMessage("Response from inner agent")
    val model = DummyModel("test") { flowOf(LlmResponse(content = responseContent)) }
    val agent = LlmAgent(name = "inner-agent", model = model)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    val result = tool.run(context, mapOf("request" to "Hello"))

    assertEquals("Response from inner agent", result)
  }

  /**
   * Parent [ToolContext] must see the wrapped agent's state changes (last-event `stateDelta`) and
   * any artifacts the wrapped agent saved via the forwarding artifact service (recorded in
   * `actions.artifactDelta` per save).
   */
  @Test
  fun run_executesInnerAgent_propagatesActions() = runTest {
    val responseContent = modelMessage("Response from inner agent")
    val eventActions = EventActions(stateDelta = mutableMapOf("testStateKey" to "testStateValue"))
    val artifactPart = Part(text = "artifact-bytes")
    val agent =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = { ctx ->
          // Drive an artifact save through the child's artifact service, which is
          // [ForwardingArtifactService] when invoked via [AgentTool].
          val service =
            ctx.artifactService
              ?: error("expected child invocation to have a (forwarding) artifact service")
          val unused = service.saveArtifact(ctx.session.key, "testArtifactKey", artifactPart)
          emit(Event(author = "inner-agent", content = responseContent, actions = eventActions))
        },
      )
    val tool = AgentTool(agent)
    val parentArtifactService = InMemoryArtifactService()
    val context =
      testToolContext(testInvocationContext(agent = agent, artifactService = parentArtifactService))

    val result = tool.run(context, mapOf("request" to "Hello"))

    assertEquals("Response from inner agent", result)
    assertEquals("testStateValue", context.actions.stateDelta["testStateKey"])
    // The forwarding service must record the save into the parent's actions.artifactDelta...
    assertEquals(0, context.actions.artifactDelta["testArtifactKey"])
    // ...and the artifact bytes must land on the parent's artifact service.
    assertEquals(
      artifactPart,
      parentArtifactService.loadArtifact(context.invocationContext.session.key, "testArtifactKey"),
    )
  }

  /**
   * The agent wrapped by an [AgentTool] runs in a separate runner/invocation, so it starts on a
   * fresh (null) branch regardless of the caller's branch -- AgentTool isolates via its own runner,
   * not via the branch field. Mirrors Python ADK 1.x.
   */
  @Test
  fun run_wrappedAgent_runsOnFreshBranch() = runTest {
    val branches = mutableListOf<String?>()
    val inner =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = { ctx ->
          branches.add(ctx.branch)
          emit(Event(author = "inner-agent", content = modelMessage("done")))
        },
      )
    val tool = AgentTool(inner)
    // The caller runs on a non-null branch; the wrapped agent must still start fresh.
    val context = testToolContext(testInvocationContext(agent = inner, branch = "caller.branch"))

    val unused = tool.run(context, mapOf("request" to "Hello"))

    assertEquals(listOf<String?>(null), branches)
  }

  /**
   * The wrapped agent must run in an isolated session: it must not see the parent's history events,
   * and it must observe a different session id than the parent. Mirrors Python ADK 1.x.
   */
  @Test
  fun run_wrappedAgent_runsInIsolatedSession() = runTest {
    var observedSession: Session? = null
    val parentEvent =
      Event(author = "user", content = modelMessage("parent history that should not leak"))
    val parentSessionService = InMemorySessionService()
    val parentSession =
      parentSessionService.createSession(
        key = com.google.adk.kt.sessions.SessionKey("parentApp", "user1", "parent-session"),
        state = null,
      )
    parentSession.events.add(parentEvent)

    val inner =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = { ctx ->
          observedSession = ctx.session
          emit(Event(author = "inner-agent", content = modelMessage("done")))
        },
      )
    val tool = AgentTool(inner)
    val context =
      testToolContext(
        testInvocationContext(
          agent = inner,
          session = parentSession,
          sessionService = parentSessionService,
        )
      )

    val unused = tool.run(context, mapOf("request" to "Hello"))

    val childSession = assertNotNull(observedSession)
    // Child must have a fresh session id, different from the parent's.
    assertEquals(true, childSession.key.id != parentSession.key.id)
    // Child must not see the parent's history events.
    assertEquals(false, childSession.events.any { it === parentEvent })
    // Parent session must not be appended to by the child run.
    assertEquals(listOf(parentEvent), parentSession.events)
  }

  /**
   * The child session must be seeded from the parent session's merged state (not the current tool
   * call's empty `actions.stateDelta`), with ADK-internal (`_adk`) and temporary (`temp:`) keys
   * excluded.
   */
  @Test
  fun run_wrappedAgent_seedsChildFromParentSessionState() = runTest {
    var observedState: Map<String, Any>? = null
    val inner =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = { ctx ->
          observedState = ctx.session.state
          emit(Event(author = "inner-agent", content = modelMessage("done")))
        },
      )
    val tool = AgentTool(inner)
    val parentSession =
      Session(
        key = com.google.adk.kt.sessions.SessionKey("parentApp", "user1", "parent-session"),
        state =
          com.google.adk.kt.sessions.State(
            initialState =
              mapOf(
                "userKey" to "userValue",
                "_adkInternal" to "should-not-leak",
                "temp:ephemeral" to "should-not-cross",
              )
          ),
      )
    val context = testToolContext(testInvocationContext(agent = inner, session = parentSession))
    // The buggy path seeded from actions.stateDelta; ensure that path is not the source.
    context.actions.stateDelta["fromDelta"] = "should-not-be-used"

    val unused = tool.run(context, mapOf("request" to "Hello"))

    val state = assertNotNull(observedState)
    assertEquals("userValue", state["userKey"])
    assertEquals(false, state.containsKey("_adkInternal"))
    assertEquals(false, state.containsKey("temp:ephemeral"))
    assertEquals(false, state.containsKey("fromDelta"))
  }

  @Test
  fun run_withSkipSummarization_setsFlagInContext() = runTest {
    val responseContent = modelMessage("Response")
    val model = DummyModel("test") { flowOf(LlmResponse(content = responseContent)) }
    val agent = LlmAgent(name = "inner-agent", model = model)
    val tool = AgentTool(agent, skipSummarization = true)
    val context = testToolContext(testInvocationContext(agent = agent))

    val unused = tool.run(context, mapOf("request" to "Hello"))

    assertEquals(true, context.actions.skipSummarization)
  }

  @Test
  fun declaration_returnsCorrectTool() {
    val agent = LlmAgent(name = "inner-agent", model = DummyModel("test"))
    val tool = AgentTool(agent)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("inner-agent", declaration.name)
  }

  @Test
  fun declaration_withNonLlmAgent_resolvesSubAgentSchema() {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("query" to Schema(type = Type.STRING)),
        required = listOf("query"),
      )
    val inner =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val wrapper =
      com.google.adk.kt.testing.DummyAgent(name = "wrapper-agent", subAgents = listOf(inner))
    val tool = AgentTool(wrapper)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("wrapper-agent", declaration.name)
    assertEquals(inputSchema, declaration.parameters)
  }

  @Test
  fun declaration_withNonLlmAgentNoSubAgents_fallsBackToRequest() {
    val wrapper = com.google.adk.kt.testing.DummyAgent(name = "wrapper-agent")
    val tool = AgentTool(wrapper)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("wrapper-agent", declaration.name)
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(1, parameters.properties?.size)
    assertEquals(Type.STRING, parameters.properties?.get("request")?.type)
  }

  @Test
  fun run_throughInMemoryRunner_executesSuccessfully() = runTest {
    val agentTool =
      AgentTool(
        LlmAgent(
          name = "inner-agent",
          model =
            DummyModel("inner-model") {
              flowOf(LlmResponse(content = modelMessage("Response from inner agent")))
            },
        )
      )
    val mainModel =
      DummyModel(
        name = "main-model",
        flows =
          listOf(
            flowOf(
              modelFunctionCallResponse(
                name = "inner-agent",
                args = mapOf("request" to "Hello inner"),
              )
            ),
            flowOf(LlmResponse(content = modelMessage("Final Answer"))),
          ),
      )
    val mainAgent = LlmAgent(name = "main-agent", model = mainModel, tools = listOf(agentTool))
    val runner = InMemoryRunner(agent = mainAgent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      listOf(
        "main-agent" to
          Part(
            functionCall = FunctionCall("inner-agent", args = mapOf("request" to "Hello inner"))
          ),
        "main-agent" to
          Part(
            functionResponse =
              FunctionResponse(
                "inner-agent",
                response = mapOf("result" to "Response from inner agent"),
              )
          ),
        "main-agent" to "Final Answer",
      ),
      simplifyEvents(events),
    )
  }

  @Test
  fun run_withValidInputSchema_executesInnerAgent() = runTest {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("name" to Schema(type = Type.STRING), "age" to Schema(type = Type.INTEGER)),
        required = listOf("name"),
      )
    val responseContent = modelMessage("Hello John")
    val model = DummyModel("test") { flowOf(LlmResponse(content = responseContent)) }
    val agent = LlmAgent(name = "inner-agent", model = model, inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    val result = tool.run(context, mapOf("name" to "John", "age" to 30L))

    assertEquals("Hello John", result)
  }

  @Test
  fun run_withInvalidInputSchema_throwsIllegalArgumentException() = runTest {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("age" to Schema(type = Type.INTEGER)),
        required = listOf("age"),
      )
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    assertFailsWith<IllegalArgumentException> { tool.run(context, mapOf("age" to "not-a-number")) }
  }

  @Test
  fun run_withMissingRequiredArg_throwsIllegalArgumentException() = runTest {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    assertFailsWith<IllegalArgumentException> { tool.run(context, emptyMap()) }
  }

  @Test
  fun run_withExtraArgNotInSchema_throwsIllegalArgumentException() = runTest {
    val inputSchema =
      Schema(type = Type.OBJECT, properties = mapOf("name" to Schema(type = Type.STRING)))
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    assertFailsWith<IllegalArgumentException> {
      tool.run(context, mapOf("name" to "John", "unexpected" to "value"))
    }
  }

  /** Plugin that counts [beforeAgent] invocations per agent name. */
  private class TrackingPlugin(override val name: String = "tracking") : Plugin {
    val beforeAgentCalls = mutableMapOf<String, Int>()

    override suspend fun beforeAgent(
      context: CallbackContext
    ): CallbackChoice<EventActions, com.google.adk.kt.types.Content> {
      val agentName = context.agent.name
      beforeAgentCalls[agentName] = (beforeAgentCalls[agentName] ?: 0) + 1
      return CallbackChoice.Continue(EventActions())
    }
  }

  /**
   * Parent runner plugins must propagate to the wrapped agent's runner by default. Mirrors Python
   * ADK 1.x `test_include_plugins_default_true`.
   */
  @Test
  fun run_throughInMemoryRunner_includePluginsDefaultTrue_propagatesParentPlugins() = runTest {
    val plugin = TrackingPlugin()
    val toolAgent =
      LlmAgent(
        name = "tool_agent",
        model =
          DummyModel("inner-model") {
            flowOf(LlmResponse(content = modelMessage("Response from inner agent")))
          },
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel(
            name = "root-model",
            flows =
              listOf(
                flowOf(
                  modelFunctionCallResponse(
                    name = "tool_agent",
                    args = mapOf("request" to "Hello inner"),
                  )
                ),
                flowOf(LlmResponse(content = modelMessage("Final Answer"))),
              ),
          ),
        tools = listOf(AgentTool(toolAgent)),
      )
    val runner =
      InMemoryRunner(App(appName = "test_app", rootAgent = rootAgent, plugins = listOf(plugin)))

    val unused =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    // beforeAgent must fire for both root_agent and tool_agent.
    assertEquals(1, plugin.beforeAgentCalls["root_agent"])
    assertEquals(1, plugin.beforeAgentCalls["tool_agent"])
  }

  /** Mirrors Python `test_include_plugins_explicit_true`. */
  @Test
  fun run_throughInMemoryRunner_includePluginsExplicitTrue_propagatesParentPlugins() = runTest {
    val plugin = TrackingPlugin()
    val toolAgent =
      LlmAgent(
        name = "tool_agent",
        model = DummyModel("inner-model") { flowOf(LlmResponse(content = modelMessage("ok"))) },
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel(
            name = "root-model",
            flows =
              listOf(
                flowOf(
                  modelFunctionCallResponse(
                    name = "tool_agent",
                    args = mapOf("request" to "Hello inner"),
                  )
                ),
                flowOf(LlmResponse(content = modelMessage("Final Answer"))),
              ),
          ),
        tools = listOf(AgentTool(toolAgent, includePlugins = true)),
      )
    val runner =
      InMemoryRunner(App(appName = "test_app", rootAgent = rootAgent, plugins = listOf(plugin)))

    val unused =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(1, plugin.beforeAgentCalls["root_agent"])
    assertEquals(1, plugin.beforeAgentCalls["tool_agent"])
  }

  /** Mirrors Python `test_include_plugins_false`. */
  @Test
  fun run_throughInMemoryRunner_includePluginsFalse_doesNotPropagateParentPlugins() = runTest {
    val plugin = TrackingPlugin()
    val toolAgent =
      LlmAgent(
        name = "tool_agent",
        model = DummyModel("inner-model") { flowOf(LlmResponse(content = modelMessage("ok"))) },
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel(
            name = "root-model",
            flows =
              listOf(
                flowOf(
                  modelFunctionCallResponse(
                    name = "tool_agent",
                    args = mapOf("request" to "Hello inner"),
                  )
                ),
                flowOf(LlmResponse(content = modelMessage("Final Answer"))),
              ),
          ),
        tools = listOf(AgentTool(toolAgent, includePlugins = false)),
      )
    val runner =
      InMemoryRunner(App(appName = "test_app", rootAgent = rootAgent, plugins = listOf(plugin)))

    val unused =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    // Plugin only fires for root_agent; the wrapped agent runs without parent plugins.
    assertEquals(1, plugin.beforeAgentCalls["root_agent"])
    assertEquals(null, plugin.beforeAgentCalls["tool_agent"])
  }

  /**
   * Artifacts must flow bidirectionally between the parent and the wrapped agent: the parent writes
   * `artifact_1`; the wrapped agent reads it and writes `artifact_2`; the parent then reads
   * `artifact_2` and writes `artifact_3`. Mirrors Python ADK 1.x `test_update_artifacts`.
   */
  @Test
  fun run_throughInMemoryRunner_forwardsArtifactsBetweenParentAndChild() = runTest {
    val toolAgent =
      LlmAgent(
        name = "tool_agent",
        model = DummyModel("inner-model") { flowOf(LlmResponse(content = modelMessage("ok"))) },
        beforeAgentCallbacks =
          listOf(
            BeforeAgentCallback { cb ->
              val a1 = cb.loadArtifact("artifact_1")
              assertNotNull(a1)
              assertEquals("test", a1.text)
              val unused = cb.saveArtifact("artifact_2", Part(text = a1.text + " 2"))
              CallbackChoice.Continue(EventActions())
            }
          ),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel(
            name = "root-model",
            flows =
              listOf(
                flowOf(
                  modelFunctionCallResponse(
                    name = "tool_agent",
                    args = mapOf("request" to "Hello inner"),
                  )
                ),
                flowOf(LlmResponse(content = modelMessage("Final Answer"))),
              ),
          ),
        tools = listOf(AgentTool(toolAgent)),
        beforeAgentCallbacks =
          listOf(
            BeforeAgentCallback { cb ->
              val unused = cb.saveArtifact("artifact_1", Part(text = "test"))
              CallbackChoice.Continue(EventActions())
            }
          ),
        afterAgentCallbacks =
          listOf(
            AfterAgentCallback { cb ->
              val a2 = cb.loadArtifact("artifact_2")
              assertNotNull(a2)
              assertEquals("test 2", a2.text)
              val unused = cb.saveArtifact("artifact_3", Part(text = a2.text + " 3"))
              CallbackChoice.Continue(Unit)
            }
          ),
      )
    val parentArtifactService = InMemoryArtifactService()
    val sessionService = InMemorySessionService()
    val app = App(appName = "test_app", rootAgent = rootAgent)
    val runner =
      InMemoryRunner(
        app = app,
        sessionService = sessionService,
        artifactService = parentArtifactService,
      )
    val parentSession =
      sessionService.createSession(
        key = com.google.adk.kt.sessions.SessionKey("test_app", "test_user", "test_session"),
        state = null,
      )

    val unused =
      runner
        .runAsync(
          userId = parentSession.key.userId,
          sessionId = parentSession.key.id!!,
          newMessage = userMessage("hi"),
        )
        .toList()

    // All three artifacts must land on the parent's artifact service under the parent session.
    val keys = parentArtifactService.listArtifactKeys(parentSession.key).sorted()
    assertEquals(listOf("artifact_1", "artifact_2", "artifact_3"), keys)
    assertEquals(
      Part(text = "test"),
      parentArtifactService.loadArtifact(parentSession.key, "artifact_1"),
    )
    assertEquals(
      Part(text = "test 2"),
      parentArtifactService.loadArtifact(parentSession.key, "artifact_2"),
    )
    assertEquals(
      Part(text = "test 2 3"),
      parentArtifactService.loadArtifact(parentSession.key, "artifact_3"),
    )
  }

  @Test
  fun run_withPropagateGroundingMetadata_propagatesToParentState() = runTest {
    val grounding = GroundingMetadata()
    val agent =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = {
          emit(
            Event(
              author = "inner-agent",
              content = modelMessage("done"),
              groundingMetadata = grounding,
            )
          )
        },
      )
    val tool = AgentTool(agent, propagateGroundingMetadata = true)
    val context = testToolContext(testInvocationContext(agent = agent))

    val unused = tool.run(context, mapOf("request" to "Hello"))

    assertEquals(grounding, context.actions.stateDelta["temp:_adk_grounding_metadata"])
  }

  @Test
  fun run_withoutPropagateGroundingMetadata_doesNotPropagate() = runTest {
    val agent =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = {
          emit(
            Event(
              author = "inner-agent",
              content = modelMessage("done"),
              groundingMetadata = GroundingMetadata(),
            )
          )
        },
      )
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    val unused = tool.run(context, mapOf("request" to "Hello"))

    assertEquals(null, context.actions.stateDelta["temp:_adk_grounding_metadata"])
  }
}
