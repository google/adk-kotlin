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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Runs [child] through [Context.runNode], optionally in [scope], on [childInput] or, with
 * [forwardInput], on this node's own input, and emits what it returned.
 */
private class ScopedDispatcher(
  override val name: String,
  private val child: Node,
  private val scope: String? = null,
  private val childInput: Any? = null,
  private val forwardInput: Boolean = false,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val input = if (forwardInput) nodeInput else childInput
    emit(context.runNode(child, nodeInput = input, overrideIsolationScope = scope))
  }
}

/** Runs [first] in scope `task-1`, then [second] in scope `task-2`. */
private class TwoScopeDispatcher(
  override val name: String,
  private val first: Node,
  private val second: Node,
) : Node {
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val unused =
      context.runNode(first, nodeInput = "approve the plan", overrideIsolationScope = "task-1")
    emit(context.runNode(second, nodeInput = "summarize", overrideIsolationScope = "task-2"))
  }
}

/**
 * A model that records the contents of every request it receives. It answers with the next of
 * [turns], or with plain [reply] text once they run out.
 */
private class ScopeRecordingModel(
  private val reply: String = "done",
  private val turns: List<Content> = emptyList(),
  private val onRequest: () -> Unit = {},
) : Model {
  override val name = "scope-recording"
  val requests = mutableListOf<List<Content>>()

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    val turn = turns.getOrNull(requests.size) ?: Content.fromText(Role.MODEL, reply)
    requests.add(request.contents)
    onRequest()
    return flowOf(LlmResponse(content = turn))
  }
}

private fun textEvent(author: String, role: String, text: String, scope: String? = null) =
  Event(author = author, content = Content.fromText(role, text), isolationScope = scope)

/** A coordinator's call that delegates a task to [name], whose [id] names the task's scope. */
private fun delegatingCallEvent(name: String, args: Map<String, Any?>, id: String = "task-1") =
  Event(
    author = "coordinator",
    content =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(functionCall = FunctionCall(name, args, id = id))),
      ),
  )

private fun Content.texts(): List<String> = parts.mapNotNull { it.text }

private const val NO_REPLY_NUDGE = "You will not receive any user replies"

class IsolationScopeTest {

  @Test
  fun runNode_scopeOverride_stampsChildAndDescendantEvents() = runBlocking {
    // Arrange: the leaf is dispatched without an override, so it inherits its parent's scope.
    val inner = ScopedDispatcher("inner", Emitter("leaf", "value"))
    val outer = ScopedDispatcher("outer", inner, scope = "task-1")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, outer)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert
    fun scopeAt(path: String) = events.first { it.nodeInfo?.path == path }.isolationScope
    assertEquals("task-1", scopeAt("wf@1/outer@1/inner@1/leaf@1"))
    assertEquals("task-1", scopeAt("wf@1/outer@1/inner@1"))
    assertNull(scopeAt("wf@1/outer@1"))
  }

  @Test
  fun runNode_nestedScopeOverride_replacesInheritedScope() = runBlocking {
    // Arrange
    val inner = ScopedDispatcher("inner", Emitter("leaf", "value"), scope = "task-2")
    val outer = ScopedDispatcher("outer", inner, scope = "task-1")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, outer)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert
    val leafEvent = events.first { it.nodeInfo?.path == "wf@1/outer@1/inner@1/leaf@1" }
    assertEquals("task-2", leafEvent.isolationScope)
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun runAsync_invocationScope_isInheritedByRootContext() = runBlocking {
    // Arrange: the invocation already runs in a scope, as a scoped agent's tool call does.
    val base = testInvocationContext()
    val scoped = base.copy(frameworkData = base.frameworkData.copy(isolationScope = "task-1"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("leaf", "value"))))

    // Act
    val events = workflow.runAsync(scoped).toList()

    // Assert
    assertEquals("task-1", events.first { it.nodeInfo?.path == "wf@1/leaf@1" }.isolationScope)
  }

  @Test
  fun runNode_assignedOutput_isStampedWithNodeScope() = runBlocking {
    // Arrange: an assigned output leaves on an event the node runner builds itself.
    val dispatcher =
      ScopedDispatcher("assigner", OutputAssigner("leaf", "assigned"), scope = "task-1")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert
    val outputEvent = events.single { it.nodeInfo?.path == "wf@1/assigner@1/leaf@1" }
    assertEquals("assigned", outputEvent.output)
    assertEquals("task-1", outputEvent.isolationScope)
  }

  @Test
  fun runNode_failingScopedNode_errorEventCarriesScope() = runBlocking {
    // Arrange
    val dispatcher =
      ScopedDispatcher(
        "failer",
        ThrowingNode("leaf") { IllegalStateException("boom") },
        scope = "task-2",
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))
    val events = mutableListOf<Event>()

    // Act
    val unused =
      assertFailsWith<IllegalStateException> {
        workflow.runAsync(testInvocationContext()).collect { events.add(it) }
      }

    // Assert
    val errorEvent = events.single { it.nodeInfo?.path == "wf@1/failer@1/leaf@1" }
    assertEquals("task-2", errorEvent.isolationScope)
  }

  @Test
  fun contents_scopedAgent_seesDelegatedTaskAndOwnScopeOnly() = runBlocking {
    // Arrange: the delegating call's id names the scope; other scopes' history stays hidden.
    val model = ScopeRecordingModel()
    val writer =
      LlmAgent(name = "writer", model = model, includeContents = LlmAgent.IncludeContents.DEFAULT)
    val session = testSession()
    session.events.add(textEvent("user", Role.USER, "hello"))
    session.events.add(delegatingCallEvent("writer", mapOf("request" to "write a poem")))
    session.events.add(textEvent("writer", Role.MODEL, "first draft", scope = "task-1"))
    session.events.add(textEvent("other", Role.MODEL, "unrelated", scope = "task-2"))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ScopedDispatcher("coordinator", writer, scope = "task-1"))),
      )

    // Act
    val unused = workflow.runAsync(testInvocationContext(session = session)).toList()

    // Assert: the formatted task opens the history, followed by the agent's own turn.
    val contents = model.requests.single()
    assertEquals(2, contents.size)
    assertEquals(Role.USER, contents[0].role)
    assertEquals("{\"request\":\"write a poem\"}", contents[0].texts()[0])
    assertContains(contents[0].texts()[1], NO_REPLY_NUDGE)
    assertEquals(listOf("first draft"), contents[1].texts())
  }

  @Test
  fun contents_taskArgsWithoutJsonForm_openWithTheirText() = runBlocking {
    // Arrange
    val model = ScopeRecordingModel()
    val writer =
      LlmAgent(name = "writer", model = model, includeContents = LlmAgent.IncludeContents.DEFAULT)
    val session = testSession()
    session.events.add(delegatingCallEvent("writer", mapOf("when" to NotJson)))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ScopedDispatcher("coordinator", writer, scope = "task-1"))),
      )

    // Act
    val unused = workflow.runAsync(testInvocationContext(session = session)).toList()

    // Assert: as in Python, the task still opens the history, as the arguments' text.
    assertEquals("{when=later}", model.requests.single()[0].texts()[0])
  }

  @Test
  fun contents_scopedAgentWithoutDelegatingCall_fallsBackToUserMessage() = runBlocking {
    // Arrange: history-free by default, so the current turn starts at the injected node input.
    val session = testSession()
    var injectedScope: String? = null
    val model = ScopeRecordingModel {
      injectedScope = session.events.last { it.author == Role.USER }.isolationScope
    }
    val writer = LlmAgent(name = "writer", model = model)
    val context =
      testInvocationContext(
        session = session,
        userContent = Content.fromText(Role.USER, "original question"),
      )
    val dispatcher =
      ScopedDispatcher("coordinator", writer, scope = "task-1", childInput = "draft it")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val unused = workflow.runAsync(context).toList()

    // Assert: the injected input is scoped, and the user's message stands in for the task.
    assertEquals("task-1", injectedScope)
    val contents = model.requests.single()
    assertEquals(2, contents.size)
    assertEquals("original question", contents[0].texts()[0])
    assertContains(contents[0].texts()[1], NO_REPLY_NUDGE)
    assertEquals(listOf("draft it"), contents[1].texts())
  }

  @Test
  fun contents_delegatingCallWithoutArgs_fallsBackToUserMessage() = runBlocking {
    // Arrange: a delegating call with no arguments carries no task.
    val model = ScopeRecordingModel()
    val writer =
      LlmAgent(name = "writer", model = model, includeContents = LlmAgent.IncludeContents.DEFAULT)
    val session = testSession()
    session.events.add(delegatingCallEvent("writer", emptyMap()))
    session.events.add(textEvent("writer", Role.MODEL, "first draft", scope = "task-1"))
    val context =
      testInvocationContext(
        session = session,
        userContent = Content.fromText(Role.USER, "original question"),
      )
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ScopedDispatcher("coordinator", writer, scope = "task-1"))),
      )

    // Act
    val unused = workflow.runAsync(context).toList()

    // Assert: the user's message stands in for the task.
    assertEquals("original question", model.requests.single()[0].texts()[0])
  }

  @Test
  fun contents_historyFreeScopedAgentWithoutTurn_sendsNoContents() = runBlocking {
    // Arrange: a delegating call and a user message exist, but nothing in the scope starts a turn.
    val model = ScopeRecordingModel()
    val writer = LlmAgent(name = "writer", model = model)
    val session = testSession()
    session.events.add(delegatingCallEvent("writer", mapOf("request" to "x")))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ScopedDispatcher("coordinator", writer, scope = "task-1"))),
      )

    // Act
    val context =
      testInvocationContext(
        session = session,
        userContent = Content.fromText(Role.USER, "original question"),
      )
    val unused = workflow.runAsync(context).toList()

    // Assert
    assertTrue(model.requests.single().isEmpty())
  }

  @Test
  fun contents_scopedWorkflowAgent_passesScopeToItsSubAgent() = runBlocking {
    // Arrange: a sequential agent dispatched in a scope runs an LLM sub-agent.
    val model = ScopeRecordingModel()
    val sequence =
      SequentialAgent(
        name = "sequence",
        subAgents = listOf(LlmAgent(name = "writer", model = model)),
      )
    val session = testSession()
    session.events.add(delegatingCallEvent("sequence", mapOf("request" to "x")))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ScopedDispatcher("coordinator", sequence, scope = "task-1"))),
      )

    // Act
    val unused = workflow.runAsync(testInvocationContext(session = session)).toList()

    // Assert: the sub-agent filters by the scope, so its history opens with the delegated task.
    assertEquals("{\"request\":\"x\"}", model.requests.single()[0].texts()[0])
  }

  @Test
  fun contents_unscopedAgent_doesNotSeeScopedEvents() = runBlocking {
    // Arrange
    val model = ScopeRecordingModel()
    val agent =
      LlmAgent(name = "agent", model = model, includeContents = LlmAgent.IncludeContents.DEFAULT)
    val session = testSession()
    session.events.add(textEvent("user", Role.USER, "hello"))
    session.events.add(textEvent("worker", Role.MODEL, "scoped secret", scope = "task-1"))
    val context =
      testInvocationContext(session = session, userContent = Content.fromText(Role.USER, "go"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, agent)))

    // Act
    val unused = workflow.runAsync(context).toList()

    // Assert: no scoped event and no task input reach an unscoped agent.
    val texts = model.requests.single().flatMap { it.texts() }
    assertEquals(listOf("hello", "go"), texts)
  }

  @Test
  fun runAsync_functionResponseMessage_takesScopeOfItsCall() = runBlocking {
    // Arrange: a scoped agent that keeps history pauses on a call the user answers next turn.
    val pausingCall =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(functionCall = FunctionCall("approve", emptyMap(), id = "call-1"))),
      )
    val model = ScopeRecordingModel(turns = listOf(pausingCall), reply = "approved")
    val worker =
      LlmAgent(
        name = "worker",
        model = model,
        includeContents = LlmAgent.IncludeContents.DEFAULT,
        tools = listOf(DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val dispatcher =
      ScopedDispatcher("coordinator", worker, scope = "task-1", childInput = "approve the plan")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode = workflow,
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val first = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val answer =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse =
                FunctionResponse(id = "call-1", name = "approve", response = mapOf("ok" to true))
            )
          ),
      )

    // Act
    val unused = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert
    assertEquals("task-1", first.single { it.longRunningToolIds.isNotEmpty() }.isolationScope)
    val session = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s")))
    val answerEvent = session.events.single { it.author == Role.USER && it.content == answer }
    assertEquals("task-1", answerEvent.isolationScope)
    assertContains(model.requests.first().flatMap { it.texts() }, "approve the plan")
    val resumedParts = model.requests.last().flatMap { it.parts }
    assertTrue(resumedParts.any { it.functionResponse?.id == "call-1" })
  }

  @Test
  fun runAsync_answerWithoutId_opensScopedHistoryWithTheAnswer() = runBlocking {
    // Arrange: an answer with no id starts a new invocation instead of resuming one. The writer
    // keeps its history, so its opening turn comes from the user content alone.
    val model = ScopeRecordingModel()
    val writer =
      LlmAgent(name = "writer", model = model, includeContents = LlmAgent.IncludeContents.DEFAULT)
    val forward = ScopedDispatcher("coordinator", writer, scope = "task-1", forwardInput = true)
    val runner =
      InMemoryRunner(app = App("app", rootNode = Workflow("wf", listOf(Edge(Start, forward)))))
    val answer =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse = FunctionResponse(name = "approve", response = mapOf("ok" to true))
            )
          ),
      )

    // Act
    val unused = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: as in Python, the answer is the user content, so it opens the scoped history.
    val opening = model.requests.single().first()
    assertEquals("approve", opening.parts.first().functionResponse?.name)
    assertContains(opening.texts().single(), NO_REPLY_NUDGE)
  }

  @Test
  fun runAsync_scopedSingleTurnNodeFedTheUserMessage_seesItInItsScope() = runBlocking {
    // Arrange: the runner records the user message unscoped, so the scoped node needs its own copy.
    val model = ScopeRecordingModel()
    val writer = LlmAgent(name = "writer", model = model)
    val forward = ScopedDispatcher("coordinator", writer, scope = "task-1", forwardInput = true)
    val runner =
      InMemoryRunner(app = App("app", rootNode = Workflow("wf", listOf(Edge(Start, forward)))))

    // Act
    val unused =
      runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "write a poem")).toList()

    // Assert: as in Python, the scoped opening carries the user message and the nudge, and the
    // node's own input follows it.
    val request = model.requests.single()
    assertEquals(listOf(Role.USER, Role.USER), request.map { it.role })
    assertEquals(2, request[0].texts().size)
    assertEquals("write a poem", request[0].texts()[0])
    assertContains(request[0].texts()[1], NO_REPLY_NUDGE)
    assertEquals(listOf("write a poem"), request[1].texts())
  }

  @Test
  fun runAsync_resumeAnswer_staysOutOfOtherScopes() = runBlocking {
    // Arrange: approver (task-1) pauses on a long-running call; summarizer (task-2) runs after it.
    val pausingCall =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(functionCall = FunctionCall("approve", emptyMap(), id = "call-1"))),
      )
    val approverModel = ScopeRecordingModel(turns = listOf(pausingCall), reply = "approved")
    val approver =
      LlmAgent(
        name = "approver",
        model = approverModel,
        includeContents = LlmAgent.IncludeContents.DEFAULT,
        tools = listOf(DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val summarizerModel = ScopeRecordingModel(reply = "summary")
    val summarizer = LlmAgent(name = "summarizer", model = summarizerModel)
    val dispatcher = TwoScopeDispatcher("coordinator", approver, summarizer)
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val unusedFirst =
      runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val answer =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse =
                FunctionResponse(
                  id = "call-1",
                  name = "approve",
                  response = mapOf("secret" to "s3cr3t"),
                )
            )
          ),
      )

    // Act
    val unusedSecond = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: as in Python, both scoped histories open with the user's original message.
    val summarizerRequest = summarizerModel.requests.single()
    assertTrue(summarizerRequest.flatMap { it.parts }.none { it.functionResponse != null })
    assertEquals("go", summarizerRequest[0].texts().first())
    assertEquals("go", approverModel.requests.last()[0].texts().first())
  }
}

/** A value with no JSON form, as only a programmatic caller can put in call arguments. */
private object NotJson {
  override fun toString() = "later"
}
