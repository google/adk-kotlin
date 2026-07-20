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

@file:OptIn(com.google.adk.kt.annotations.ExperimentalContextCachingFeature::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.summarizer.EventSummarizer
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.MonotonicTimestampSessionService
import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class AbstractRunnerTest {

  class TestRunner(agent: BaseAgent, resumable: Boolean = true) :
    InMemoryRunner(
      App(
        appName = "InMemoryRunner",
        rootAgent = agent,
        resumabilityConfig = ResumabilityConfig(isResumable = resumable),
      )
    ) {
    suspend fun callFindAgentToRun(context: InvocationContext, rootAgent: BaseAgent): BaseAgent {
      return findAgentToRun(context, rootAgent)
    }
  }

  @Test
  fun findAgentToRun_withFunctionResponse_returnsTargetAgent() = runTest {
    val subAgent = DummyAgent("sub")
    val rootAgent = DummyAgent("root", subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val callId = "call-123"
    val callEvent =
      Event(
        author = "sub",
        content =
          Content(
            Role.MODEL,
            listOf(Part(functionCall = FunctionCall("tool", emptyMap(), callId))),
          ),
        invocationId = "inv-1",
      )
    val responseEvent =
      Event(
        author = Role.USER,
        content = userFunctionResponse(name = "tool", id = callId),
        invocationId = "inv-1",
      )

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, callEvent)
    val unused2 = runner.sessionService.appendEvent(session, responseEvent)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("sub", result.name)
  }

  @Test
  fun findAgentToRun_functionResponse_nonTransferableCallingAgent_returnsThatAgent() = runTest {
    // Function-response routing returns the agent that issued the call regardless of its
    // transferability -- the response belongs to that agent. Mirrors Python ADK 1.x
    // `_find_agent_to_run` (unconditional `find_agent(event.author)` for a function response).
    val subAgent = DummyAgent("specialist", disallowTransferToParent = true)
    val rootAgent = DummyAgent("root", subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val callId = "call-nt"
    val callEvent =
      Event(
        author = "specialist",
        content =
          Content(
            Role.MODEL,
            listOf(Part(functionCall = FunctionCall("tool", emptyMap(), callId))),
          ),
        invocationId = "inv-1",
      )
    val responseEvent =
      Event(
        author = Role.USER,
        content = userFunctionResponse(name = "tool", id = callId),
        invocationId = "inv-1",
      )

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, callEvent)
    val unused2 = runner.sessionService.appendEvent(session, responseEvent)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("specialist", result.name)
  }

  @Test
  fun findAgentToRun_functionCallAuthorNotAnAgent_fallsBackToRoot() = runTest {
    // The matching function-call event is authored by "user" (not an agent in the hierarchy), so
    // function-response routing is skipped and the backward scan finds no agent event, leaving the
    // root agent as the fallback.
    val subAgent = DummyAgent("sub")
    val rootAgent = DummyAgent("root", subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val callId = "call-unknown"
    val callEvent =
      Event(
        author = Role.USER,
        content =
          Content(
            Role.MODEL,
            listOf(Part(functionCall = FunctionCall("tool", emptyMap(), callId))),
          ),
        invocationId = "inv-1",
      )
    val responseEvent =
      Event(
        author = Role.USER,
        content = userFunctionResponse(name = "tool", id = callId),
        invocationId = "inv-1",
      )

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, callEvent)
    val unused2 = runner.sessionService.appendEvent(session, responseEvent)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("root", result.name)
  }

  @Test
  fun findAgentToRun_staleFunctionCallAuthor_fallsBackToRoot() = runTest {
    // The matching function-call event is authored by an agent that is not in the current
    // hierarchy (e.g. carried over from a previous session). `findAgent` returns null and the
    // routing falls back to the root agent instead of propagating a null.
    val subAgent = DummyAgent("sub")
    val rootAgent = DummyAgent("root", subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val callId = "call-stale"
    val callEvent =
      Event(
        author = "agent_from_a_previous_session",
        content =
          Content(
            Role.MODEL,
            listOf(Part(functionCall = FunctionCall("tool", emptyMap(), callId))),
          ),
        invocationId = "inv-1",
      )
    val responseEvent =
      Event(
        author = Role.USER,
        content = userFunctionResponse(name = "tool", id = callId),
        invocationId = "inv-1",
      )

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, callEvent)
    val unused2 = runner.sessionService.appendEvent(session, responseEvent)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("root", result.name)
  }

  @Test
  fun findAgentToRun_noFunctionResponse_returnsMostRecentTransferableAgent() = runTest {
    // isTransferableAcrossAgentTree requires every ancestor to be an LlmAgent (mirroring Python's
    // _is_transferable_across_agent_tree hasattr check), so root and sub must be LlmAgents.
    val subAgent = LlmAgent(name = "sub", model = DummyModel("model"))
    val rootAgent =
      LlmAgent(name = "root", model = DummyModel("model"), subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val event1 = Event(author = "sub", content = modelMessage("Hello"), invocationId = "inv-1")
    val event2 = Event(author = Role.USER, content = userMessage("Hi"), invocationId = "inv-1")

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, event1)
    val unused2 = runner.sessionService.appendEvent(session, event2)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("sub", result.name)
  }

  @Test
  fun findAgentToRun_untransferableAgent_returnsRoot() = runTest {
    val subAgent = DummyAgent("sub", disallowTransferToParent = true)
    val rootAgent = DummyAgent("root", subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val event1 = Event(author = "sub", content = modelMessage("Hello"), invocationId = "inv-1")
    val event2 = Event(author = Role.USER, content = userMessage("Hi"), invocationId = "inv-1")

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, event1)
    val unused2 = runner.sessionService.appendEvent(session, event2)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("root", result.name)
  }

  @Test
  fun findAgentToRun_withStateEvents_skipsStateEvents() = runTest {
    // isTransferableAcrossAgentTree requires LlmAgent ancestry (see
    // findAgentToRun_noFunctionResponse_*).
    val subAgent = LlmAgent(name = "sub", model = DummyModel("model"))
    val rootAgent =
      LlmAgent(name = "root", model = DummyModel("model"), subAgents = listOf(subAgent))
    val runner = TestRunner(rootAgent)

    val event1 = Event(author = "sub", content = modelMessage("Hello"), invocationId = "inv-1")
    val event2 =
      Event(author = "sub", actions = EventActions(endOfAgent = true), invocationId = "inv-1")
    val event3 = Event(author = Role.USER, content = userMessage("Hi"), invocationId = "inv-1")

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, event1)
    val unused2 = runner.sessionService.appendEvent(session, event2)
    val unused3 = runner.sessionService.appendEvent(session, event3)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("sub", result.name)
  }

  @Test
  fun findAgentToRun_candidateUnderWorkflowAgent_fallsBackToRoot() = runTest {
    // root (LlmAgent) -> seq (SequentialAgent) -> leaf (LlmAgent). The leaf authored the last
    // (non-function-response) event, but it is nested under a workflow agent, so
    // isTransferableAcrossAgentTree must reject it (a SequentialAgent ancestor is not an LlmAgent,
    // mirroring Python's _is_transferable_across_agent_tree hasattr check). Expect fallback to
    // root.
    val leaf = LlmAgent(name = "leaf", model = DummyModel("model"))
    val seq = SequentialAgent(name = "seq", subAgents = listOf(leaf))
    val rootAgent = LlmAgent(name = "root", model = DummyModel("model"), subAgents = listOf(seq))
    val runner = TestRunner(rootAgent)

    val event1 = Event(author = "leaf", content = modelMessage("Hello"), invocationId = "inv-1")
    val event2 = Event(author = Role.USER, content = userMessage("Hi"), invocationId = "inv-1")

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, event1)
    val unused2 = runner.sessionService.appendEvent(session, event2)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("root", result.name)
  }

  @Test
  fun findAgentToRun_leafNotTransferable_returnsNearestTransferableAncestor() = runTest {
    // root (LlmAgent) -> mid (LlmAgent, transferable) -> leaf (LlmAgent, disallowTransferToParent).
    // The leaf authored the last event but cannot host the next turn, so the runner walks up and
    // routes to the nearest transferable ancestor -- the intermediate `mid` -- rather than all the
    // way to root. Mirrors Python ADK's
    // tests/unittests/workflow/test_agent_transfer.py::test_auto_to_auto_to_single (the middle
    // agent
    // handles the second turn).
    val leaf =
      LlmAgent(
        name = "leaf",
        model = DummyModel("model"),
        disallowTransferToParent = true,
        disallowTransferToPeers = true,
      )
    val mid = LlmAgent(name = "mid", model = DummyModel("model"), subAgents = listOf(leaf))
    val rootAgent = LlmAgent(name = "root", model = DummyModel("model"), subAgents = listOf(mid))
    val runner = TestRunner(rootAgent)

    // mid acted (transferable), then leaf produced the last model event, then the user replied.
    val event1 = Event(author = "mid", content = modelMessage("mid"), invocationId = "inv-1")
    val event2 = Event(author = "leaf", content = modelMessage("leaf"), invocationId = "inv-1")
    val event3 = Event(author = Role.USER, content = userMessage("Hi"), invocationId = "inv-1")

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, event1)
    val unused2 = runner.sessionService.appendEvent(session, event2)
    val unused3 = runner.sessionService.appendEvent(session, event3)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )
    val result = runner.callFindAgentToRun(context, rootAgent)

    assertEquals("mid", result.name)
  }

  @Test
  fun findAgentToRun_withDisallowTransferToPeers_throwsException() = runTest {
    val sub1 = DummyAgent("sub1", disallowTransferToPeers = true)
    val sub2 = DummyAgent("sub2")
    val rootAgent = DummyAgent("root", subAgents = listOf(sub1, sub2))
    val runner = TestRunner(rootAgent)

    val event1 =
      Event(
        author = "sub1",
        actions = EventActions(transferToAgent = "sub2"),
        invocationId = "inv-1",
      )
    val event2 = Event(author = Role.USER, content = userMessage("Hi"), invocationId = "inv-1")

    val session =
      runner.sessionService.createSession(SessionKey("InMemoryRunner", "user", "session"))
    val unused1 = runner.sessionService.appendEvent(session, event1)
    val unused2 = runner.sessionService.appendEvent(session, event2)

    val updatedSession =
      runner.sessionService.getSession(SessionKey("InMemoryRunner", "user", "session"))!!
    val context =
      InvocationContext(
        session = updatedSession,
        runConfig = null,
        agent = rootAgent,
        invocationId = "inv-1",
        sessionService = runner.sessionService,
      )

    assertFailsWith<IllegalArgumentException> { runner.callFindAgentToRun(context, rootAgent) }
      .also { assertEquals("Agent 'sub1' is not allowed to transfer to peer 'sub2'.", it.message) }
  }

  @Test
  fun rewindAsync_withStateAndArtifacts_restoresBoth() = runTest {
    val runner = InMemoryRunner(agent = DummyAgent())
    val key = SessionKey("InMemoryRunner", "user", "session")
    val artifactService = runner.artifactService!!

    val session = runner.sessionService.createSession(key)

    // invocation1: write f1 v0, set k1=v1
    val f1v0 = Part(text = "f1v0")
    val f1v0Version = artifactService.saveArtifact(session.key, "f1", f1v0)
    assertEquals(0, f1v0Version)
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation1",
          author = "agent",
          content = modelMessage("event1"),
          actions =
            EventActions(
              stateDelta = mutableMapOf("k1" to "v1"),
              artifactDelta = mutableMapOf("f1" to 0),
            ),
        ),
      )

    // invocation2: write f1 v1 and f2 v0, set k1=v2 and k2=v2
    val f1v1 = Part(text = "f1v1")
    val unusedF1v1 = artifactService.saveArtifact(session.key, "f1", f1v1)
    val f2v0 = Part(text = "f2v0")
    val unusedF2v0 = artifactService.saveArtifact(session.key, "f2", f2v0)
    val unused2 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation2",
          author = "agent",
          content = modelMessage("event2"),
          actions =
            EventActions(
              stateDelta = mutableMapOf("k1" to "v2", "k2" to "v2"),
              artifactDelta = mutableMapOf("f1" to 1, "f2" to 0),
            ),
        ),
      )

    // invocation3: set k2=v3 (no artifact change)
    val unused3 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation3",
          author = "agent",
          content = modelMessage("event3"),
          actions = EventActions(stateDelta = mutableMapOf("k2" to "v3")),
        ),
      )

    runner.rewindAsync(
      userId = "user",
      sessionId = "session",
      rewindBeforeInvocationId = "invocation2",
    )

    val rewound = runner.sessionService.getSession(key)!!
    assertEquals("v1", rewound.state["k1"])
    assertNull(rewound.state["k2"])
    assertEquals(f1v0, artifactService.loadArtifact(rewound.key, "f1"))
    // f2 did not exist at invocation2; the placeholder empty Part is what's loaded.
    assertEquals(
      Part(inlineData = Blob(mimeType = "application/octet-stream", data = ByteArray(0))),
      artifactService.loadArtifact(rewound.key, "f2"),
    )
  }

  @Test
  fun rewindAsync_notFirstInvocation_restoresStateAndArtifactsAtThatPoint() = runTest {
    val runner = InMemoryRunner(agent = DummyAgent())
    val key = SessionKey("InMemoryRunner", "user", "session")
    val artifactService = runner.artifactService!!

    val session = runner.sessionService.createSession(key)

    val f1v0 = Part(text = "f1v0")
    val unusedF1v0 = artifactService.saveArtifact(session.key, "f1", f1v0)
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation1",
          author = "agent",
          content = modelMessage("event1"),
          actions =
            EventActions(
              stateDelta = mutableMapOf("k1" to "v1"),
              artifactDelta = mutableMapOf("f1" to 0),
            ),
        ),
      )

    val f1v1 = Part(text = "f1v1")
    val unusedF1v1 = artifactService.saveArtifact(session.key, "f1", f1v1)
    val f2v0 = Part(text = "f2v0")
    val unusedF2v0 = artifactService.saveArtifact(session.key, "f2", f2v0)
    val unused2 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation2",
          author = "agent",
          content = modelMessage("event2"),
          actions =
            EventActions(
              stateDelta = mutableMapOf("k1" to "v2", "k2" to "v2"),
              artifactDelta = mutableMapOf("f1" to 1, "f2" to 0),
            ),
        ),
      )

    val unused3 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation3",
          author = "agent",
          content = modelMessage("event3"),
          actions = EventActions(stateDelta = mutableMapOf("k2" to "v3")),
        ),
      )

    runner.rewindAsync(
      userId = "user",
      sessionId = "session",
      rewindBeforeInvocationId = "invocation3",
    )

    val rewound = runner.sessionService.getSession(key)!!
    assertEquals("v2", rewound.state["k1"])
    assertEquals("v2", rewound.state["k2"])
    assertEquals(f1v1, artifactService.loadArtifact(rewound.key, "f1"))
    assertEquals(f2v0, artifactService.loadArtifact(rewound.key, "f2"))
  }

  @Test
  fun rewindAsync_unknownInvocationId_throws() = runTest {
    val runner = InMemoryRunner(agent = DummyAgent())
    val key = SessionKey("InMemoryRunner", "user", "session")

    val session = runner.sessionService.createSession(key)
    val unused =
      runner.sessionService.appendEvent(
        session,
        Event(invocationId = "invocation1", author = "agent", content = modelMessage("event1")),
      )

    assertFailsWith<IllegalArgumentException> {
        runner.rewindAsync(
          userId = "user",
          sessionId = "session",
          rewindBeforeInvocationId = "ghost",
        )
      }
      .also { assertEquals("Invocation ID not found: ghost", it.message) }
  }

  @Test
  fun rewindAsync_unknownSessionId_throws() = runTest {
    val runner = InMemoryRunner(agent = DummyAgent())

    assertFailsWith<IllegalArgumentException> {
        runner.rewindAsync(
          userId = "user",
          sessionId = "ghost-session",
          rewindBeforeInvocationId = "any",
        )
      }
      .also { assertEquals("Session not found: ghost-session", it.message) }
  }

  @Test
  fun rewindAsync_artifactServiceRejectingFileData_restoresViaInlineData() = runTest {
    val artifactService = NoFileDataArtifactService()
    val runner = InMemoryRunner(agent = DummyAgent(), artifactService = artifactService)
    val key = SessionKey("InMemoryRunner", "user", "session")

    val session = runner.sessionService.createSession(key)

    val f1v0 = Part(text = "f1v0")
    val unusedF1v0 = artifactService.saveArtifact(session.key, "f1", f1v0)
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation1",
          author = "agent",
          content = modelMessage("e1"),
          actions =
            EventActions(
              stateDelta = mutableMapOf("k1" to "v1"),
              artifactDelta = mutableMapOf("f1" to 0),
            ),
        ),
      )

    val f1v1 = Part(text = "f1v1")
    val unusedF1v1 = artifactService.saveArtifact(session.key, "f1", f1v1)
    val unused2 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation2",
          author = "agent",
          content = modelMessage("e2"),
          actions = EventActions(artifactDelta = mutableMapOf("f1" to 1)),
        ),
      )

    // Would throw UnsupportedOperationException if rewind constructed a fileData part.
    runner.rewindAsync(
      userId = "user",
      sessionId = "session",
      rewindBeforeInvocationId = "invocation2",
    )

    val rewound = runner.sessionService.getSession(key)!!
    assertEquals(f1v0, artifactService.loadArtifact(rewound.key, "f1"))
  }

  @Test
  fun rewindAsync_preRewindStateRemovedSentinel_dropsFromSnapshot() = runTest {
    val runner = InMemoryRunner(agent = DummyAgent())
    val key = SessionKey("InMemoryRunner", "user", "session")
    val session = runner.sessionService.createSession(key)

    // invocation1 sets k=v1; invocation2 deletes k via State.REMOVED. Both are before the rewind
    // target, so the snapshot at rewind point should treat k as absent.
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation1",
          author = "agent",
          content = modelMessage("e1"),
          actions = EventActions(stateDelta = mutableMapOf("k" to "v1")),
        ),
      )
    val unused2 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation2",
          author = "agent",
          content = modelMessage("e2"),
          actions = EventActions(stateDelta = mutableMapOf("k" to State.REMOVED)),
        ),
      )
    val unused3 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation3",
          author = "agent",
          content = modelMessage("e3"),
          actions = EventActions(stateDelta = mutableMapOf("k" to "v3")),
        ),
      )

    runner.rewindAsync(
      userId = "user",
      sessionId = "session",
      rewindBeforeInvocationId = "invocation3",
    )

    // k did not exist at the rewind point, so it should be removed from the current state.
    val rewound = runner.sessionService.getSession(key)!!
    assertNull(rewound.state["k"])
  }

  @Test
  fun rewindAsync_artifactLoadReturnsNull_fallsBackToEmptyPlaceholder() = runTest {
    val artifactService = MissingVersionedArtifactService()
    val runner = InMemoryRunner(agent = DummyAgent(), artifactService = artifactService)
    val key = SessionKey("InMemoryRunner", "user", "session")

    val session = runner.sessionService.createSession(key)

    // invocation1 records f1 v0 in the delta but the artifact service cannot serve version 0.
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation1",
          author = "agent",
          content = modelMessage("e1"),
          actions = EventActions(artifactDelta = mutableMapOf("f1" to 0)),
        ),
      )
    val unused2 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "invocation2",
          author = "agent",
          content = modelMessage("e2"),
          actions = EventActions(artifactDelta = mutableMapOf("f1" to 1)),
        ),
      )

    runner.rewindAsync(
      userId = "user",
      sessionId = "session",
      rewindBeforeInvocationId = "invocation2",
    )

    // Latest f1 is the empty placeholder saved by the rewind fallback path.
    assertEquals(
      Part(inlineData = Blob(mimeType = "application/octet-stream", data = ByteArray(0))),
      artifactService.lastSavedArtifact("f1"),
    )
  }

  @Test
  fun runAsync_slidingWindowConfigured_compactsAfterInterval() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 0L, endTs = 0L))
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = echoAgent(),
            eventsCompactionConfig =
              EventsCompactionConfig(
                compactionInterval = 2,
                overlapSize = 0,
                summarizer = summarizer,
              ),
          )
      )

    // The runner exposes its App (read-only); the effective compaction config is read from it.
    assertEquals(summarizer, runner.app?.eventsCompactionConfig?.summarizer)

    // First invocation: only one completed invocation, below the interval.
    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()
    assertTrue(summarizer.calls.isEmpty())

    // Second invocation: the interval is reached, so compaction fires exactly once over the four
    // raw events (user + model per invocation) from the two completed invocations.
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi again"))
      .toList()
    assertEquals(1, summarizer.calls.size)
    assertEquals(4, summarizer.calls.single().size)

    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    assertEquals(1, events.count { it.actions.compaction != null })
  }

  @Test
  fun runAsync_belowCompactionInterval_doesNotCompact() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 0L, endTs = 0L))
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = echoAgent(),
            eventsCompactionConfig =
              EventsCompactionConfig(
                compactionInterval = 3,
                overlapSize = 0,
                summarizer = summarizer,
              ),
          )
      )

    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("a")).toList()
    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("b")).toList()

    assertTrue(summarizer.calls.isEmpty())
    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    assertTrue(events.none { it.actions.compaction != null })
    // Two invocations, each appending a user and a model event, and no compaction event added.
    assertEquals(4, events.size)
  }

  @Test
  fun runAsync_threadsEventsCompactionConfigIntoInvocationContext() = runTest {
    // A token-threshold-only config (no sliding-window fields) must reach the InvocationContext so
    // intra-invocation request processors can read it.
    val summarizer = RecordingSummarizer()
    val config =
      EventsCompactionConfig(tokenThreshold = 100, eventRetentionSize = 2, summarizer = summarizer)
    var captured: EventsCompactionConfig? = null
    val agent =
      DummyAgent(name = "agent") { context ->
        captured = context.eventsCompactionConfig
        emit(
          Event(
            author = Role.MODEL,
            invocationId = context.invocationId,
            content = modelMessage("resp"),
          )
        )
      }
    val runner =
      InMemoryRunner(
        app = App(appName = "test_app", rootAgent = agent, eventsCompactionConfig = config)
      )

    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()

    assertEquals(config, captured)
  }

  @Test
  fun runAsync_noCompactionConfig_doesNotCompact() = runTest {
    val runner = InMemoryRunner(agent = echoAgent())

    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("a")).toList()
    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("b")).toList()

    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    assertTrue(events.none { it.actions.compaction != null })
  }

  @Test
  fun construct_slidingWindowWithoutSummarizerAndNonLlmAgentRoot_throws() {
    assertFailsWith<IllegalArgumentException> {
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = DummyAgent(),
            eventsCompactionConfig = EventsCompactionConfig(compactionInterval = 2, overlapSize = 0),
          )
      )
    }
  }

  @Test
  fun construct_compactionConfigWithoutStrategyAndNonLlmAgentRoot_throws() {
    // A summarizer is resolved for any compaction config (not only sliding-window), so even a
    // strategy-less config requires a model and fails fast when the root is not an LlmAgent.
    assertFailsWith<IllegalArgumentException> {
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = DummyAgent(),
            eventsCompactionConfig = EventsCompactionConfig(),
          )
      )
    }
  }

  // ----- Context cache config propagation -----

  @Test
  fun runAsync_appWithContextCacheConfig_propagatesToInvocationContext() = runTest {
    val cacheConfig = ContextCacheConfig(cacheIntervals = 5)
    var capturedConfig: ContextCacheConfig? = null
    val agent =
      DummyAgent(name = "agent") { context ->
        capturedConfig = context.contextCacheConfig
        emit(
          Event(
            author = Role.MODEL,
            invocationId = context.invocationId,
            content = modelMessage("resp"),
          )
        )
      }
    val runner =
      InMemoryRunner(
        app = App(appName = "test_app", rootAgent = agent, contextCacheConfig = cacheConfig)
      )

    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()

    assertEquals(cacheConfig, capturedConfig)
  }

  @Test
  fun runAsync_appWithoutContextCacheConfig_invocationContextConfigIsNull() = runTest {
    // Sentinel non-null so the assertion meaningfully verifies the runner left it unset.
    var capturedConfig: ContextCacheConfig? = ContextCacheConfig()
    val agent =
      DummyAgent(name = "agent") { context ->
        capturedConfig = context.contextCacheConfig
        emit(
          Event(
            author = Role.MODEL,
            invocationId = context.invocationId,
            content = modelMessage("resp"),
          )
        )
      }
    val runner = InMemoryRunner(app = App(appName = "test_app", rootAgent = agent))

    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()

    assertNull(capturedConfig)
  }

  @Test
  fun runAsync_defaultSummarizerUsesRootLlmAgentModel() = runTest {
    // No summarizer supplied: the runner must build a default LlmEventSummarizer from the root
    // LlmAgent's model, so the compaction summary content comes from that model.
    val model = DummyModel(name = "model") { flowOf(LlmResponse(content = modelMessage("OK"))) }
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = LlmAgent(name = "agent", model = model),
            eventsCompactionConfig = EventsCompactionConfig(compactionInterval = 2, overlapSize = 0),
          )
      )

    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi again"))
      .toList()

    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    val compaction = events.single { it.actions.compaction != null }
    assertEquals("OK", compaction.actions.compaction?.compactedContent?.parts?.firstOrNull()?.text)
  }

  @Test
  fun runAsync_initialStateDelta_visibleToOnUserMessageAndAgent() = runTest {
    val stateSeenByPlugin = mutableMapOf<String, Any?>()
    val stateSeenByAgent = mutableMapOf<String, Any?>()

    val plugin =
      object : Plugin {
        override val name = "state-observer"

        override suspend fun onUserMessage(
          invocationContext: InvocationContext,
          userMessage: Content,
        ): Content {
          stateSeenByPlugin["k"] = invocationContext.session.state["k"]
          return userMessage
        }
      }

    val agent =
      DummyAgent(
        name = "agent",
        onRunAsync = { context -> stateSeenByAgent["k"] = context.session.state["k"] },
      )

    val runner =
      InMemoryRunner(
        app = App(appName = "state_delta_app", rootAgent = agent, plugins = listOf(plugin))
      )

    runner
      .runAsync(
        userId = "user",
        sessionId = "session",
        newMessage = userMessage("hi"),
        stateDelta = mapOf("k" to "v"),
      )
      .toList()

    assertEquals("v", stateSeenByPlugin["k"])
    assertEquals("v", stateSeenByAgent["k"])

    // And the delta is still persisted to the session afterwards.
    val session =
      runner.sessionService.getSession(SessionKey("state_delta_app", "user", "session"))!!
    assertEquals("v", session.state["k"])
  }

  @Test
  fun runAsync_tokenThresholdConfigured_compactsWhenPromptExceedsThreshold() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 0L, endTs = 0L))
    // The model reports a prompt token count of 200 on every call, exceeding the threshold of 100.
    val model =
      DummyModel(name = "model") {
        flowOf(
          LlmResponse(
            content = modelMessage("resp"),
            usageMetadata = UsageMetadata(promptTokenCount = 200),
          )
        )
      }
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = LlmAgent(name = "agent", model = model),
            eventsCompactionConfig =
              EventsCompactionConfig(
                tokenThreshold = 100,
                eventRetentionSize = 1,
                summarizer = summarizer,
              ),
          ),
        // Deterministic, strictly-increasing timestamps so the retention boundary never lands on a
        // same-millisecond wall-clock tie (which would empty the window and skip compaction).
        sessionService = MonotonicTimestampSessionService(),
      )

    // First turn: no prior usage metadata, the char estimate is tiny, so no compaction fires.
    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()
    assertTrue(summarizer.calls.isEmpty())

    // Second turn: the prior model response reported 200 prompt tokens (>= 100), so the compaction
    // request processor fires before the model call of this turn.
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi again"))
      .toList()

    assertEquals(1, summarizer.calls.size)
    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    assertEquals(1, events.count { it.actions.compaction != null })
  }

  @Test
  fun runAsync_bothStrategiesConfigured_tokenCompactionSuppressesSlidingWindow() = runTest {
    // Records calls and produces a realistic compaction range from the window.
    val summarizer =
      object : EventSummarizer {
        val calls: MutableList<List<Event>> = mutableListOf()

        override suspend fun summarizeEvents(events: List<Event>): Event {
          calls.add(events.toList())
          return compactionEvent(
            startTs = events.first().timestamp,
            endTs = events.last().timestamp,
          )
        }
      }
    // Reports a prompt token count over the threshold, enabling intra-invocation compaction.
    val model =
      DummyModel(name = "model") {
        flowOf(
          LlmResponse(
            content = modelMessage("resp"),
            usageMetadata = UsageMetadata(promptTokenCount = 200),
          )
        )
      }
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = LlmAgent(name = "agent", model = model),
            // Both strategies on: sliding-window (post-invocation) and token-threshold
            // (intra-invocation).
            eventsCompactionConfig =
              EventsCompactionConfig(
                compactionInterval = 2,
                overlapSize = 0,
                tokenThreshold = 100,
                eventRetentionSize = 1,
                summarizer = summarizer,
              ),
          ),
        // Deterministic, strictly-increasing timestamps so the token-threshold retention boundary
        // does not land on a same-millisecond wall-clock tie (which would retain "resp" too).
        sessionService = MonotonicTimestampSessionService(),
      )

    // Turn 1: neither fires yet -- no reported token count, and below the sliding interval.
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("first"))
      .toList()
    assertTrue(summarizer.calls.isEmpty())

    // Turn 2: token-threshold fires before the model call and advances the compaction boundary, so
    // the post-invocation sliding-window sees only one new invocation (< interval) and is
    // suppressed.
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("second"))
      .toList()

    // Exactly one compaction fired, and it was token-threshold's: its window is the tail-retention
    // selection (older events, "second" kept raw). A sliding-window compaction would instead have
    // used an invocation window that includes "second".
    val windowTexts =
      summarizer.calls.single().flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.text }
    assertEquals(listOf("first", "resp"), windowTexts)

    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    assertEquals(1, events.count { it.actions.compaction != null })
  }

  @Test
  fun runAsync_tokenThreshold_firesMidInvocationInToolLoopAndFoldsPriorSummary() = runTest {
    // Records each compaction window and returns a summary tagged with the call index, so a
    // folded-in prior summary is identifiable by its text.
    val summarizer =
      object : EventSummarizer {
        val calls: MutableList<List<Event>> = mutableListOf()

        override suspend fun summarizeEvents(events: List<Event>): Event {
          calls.add(events.toList())
          return compactionEvent(
            startTs = events.first().timestamp,
            endTs = events.last().timestamp,
            summary = "SUMMARY_${calls.size}",
          )
        }
      }
    // Model calls in order: turn 1 (text), turn 2 call 1 (function call), turn 2 call 2 (final
    // text). The first two report a prompt token count over the threshold so the pre-call
    // token-threshold compaction fires before the model calls that follow them.
    var modelCalls = 0
    val model =
      DummyModel(name = "model") { _ ->
        modelCalls++
        when (modelCalls) {
          1 ->
            flowOf(
              LlmResponse(
                content = modelMessage("answer-1"),
                usageMetadata = UsageMetadata(promptTokenCount = 200),
              )
            )
          2 ->
            flowOf(
              LlmResponse(
                content =
                  Content(
                    role = Role.MODEL,
                    parts =
                      listOf(Part(functionCall = FunctionCall(name = "dummy_tool", id = "call_1"))),
                  ),
                usageMetadata = UsageMetadata(promptTokenCount = 200),
              )
            )
          else -> flowOf(LlmResponse(content = modelMessage("answer-2")))
        }
      }
    var toolCalls = 0
    val tool =
      DummyTool(name = "dummy_tool") { _, _ ->
        toolCalls++
        mapOf("status" to "done")
      }
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = LlmAgent(name = "agent", model = model, tools = listOf(tool)),
            // Token-threshold only, so the post-invocation sliding window stays out of the way.
            eventsCompactionConfig =
              EventsCompactionConfig(
                tokenThreshold = 100,
                eventRetentionSize = 1,
                summarizer = summarizer,
              ),
          ),
        // Strictly increasing timestamps keep the compaction boundary deterministic across
        // platforms (Robolectric freezes the wall clock).
        sessionService = MonotonicTimestampSessionService(),
      )

    // Turn 1: seeds a reported prompt token count; nothing to compact yet.
    runner.runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi")).toList()
    assertTrue(summarizer.calls.isEmpty())

    // Turn 2: a single invocation with a tool-call loop => two model calls. Token-threshold
    // compaction fires before each: once at invocation start, then again mid-invocation before the
    // post-tool model call.
    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("hi2"))
      .toList()

    // Two model calls happened in the single turn-2 invocation, and the tool ran once.
    assertEquals(3, modelCalls)
    assertEquals(1, toolCalls)

    // Compaction fired twice in turn 2: at invocation start and again mid-invocation (before the
    // post-tool model call).
    assertEquals(2, summarizer.calls.size)

    // The mid-invocation compaction folded in the prior summary: its window starts with the first
    // compaction's summary followed by the turn-2 user message (older events are compacted; the
    // function-call/response tail is kept raw).
    val secondWindowTexts =
      summarizer.calls[1].flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.text }
    assertEquals(listOf("SUMMARY_1", "hi2"), secondWindowTexts)

    // Both summaries are persisted to the session.
    val events =
      assertNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "user", "session")))
        .events
    assertEquals(2, events.count { it.actions.compaction != null })
  }
}

/** A [DummyAgent] that emits one model [Event] tagged with the current invocation id per turn. */
private fun echoAgent(name: String = "agent"): DummyAgent =
  DummyAgent(name = name) { context ->
    emit(
      Event(
        author = Role.MODEL,
        invocationId = context.invocationId,
        content = modelMessage("resp"),
      )
    )
  }

/**
 * An [EventSummarizer] that records every event list passed to [summarizeEvents] in [calls] and
 * returns the preconfigured [returning] event.
 */
private class RecordingSummarizer(private val returning: Event? = null) : EventSummarizer {
  val calls: MutableList<List<Event>> = mutableListOf()

  override suspend fun summarizeEvents(events: List<Event>): Event? {
    calls.add(events.toList())
    return returning
  }
}

/** Artifact service that rejects `fileData` parts, mirroring GCS/file-backed services. */
private class NoFileDataArtifactService(
  private val delegate: InMemoryArtifactService = InMemoryArtifactService()
) : ArtifactService by delegate {
  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int {
    if (artifact.fileData != null) {
      throw UnsupportedOperationException("Saving artifact with fileData is not supported.")
    }
    return delegate.saveArtifact(sessionKey, filename, artifact)
  }
}

/**
 * Artifact service that records saves but returns `null` from versioned [loadArtifact] calls,
 * mirroring a backend where the in-delta version is no longer retrievable. Exercises the rewind
 * fallback path.
 */
private class MissingVersionedArtifactService(
  private val delegate: InMemoryArtifactService = InMemoryArtifactService()
) : ArtifactService by delegate {
  private val lastSaved = mutableMapOf<String, Part>()

  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part): Int {
    lastSaved[filename] = artifact
    return delegate.saveArtifact(sessionKey, filename, artifact)
  }

  override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): Part? {
    if (version != null) return null
    return delegate.loadArtifact(sessionKey, filename, version)
  }

  fun lastSavedArtifact(filename: String): Part? = lastSaved[filename]
}
