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

@file:OptIn(ExperimentalResumabilityFeature::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AbstractRunnerTest {

  class TestRunner(agent: BaseAgent, resumable: Boolean = true) :
    InMemoryRunner(
      agent = agent,
      resumabilityConfig = ResumabilityConfig(isResumable = resumable),
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
    val subAgent = DummyAgent("sub")
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
    val subAgent = DummyAgent("sub")
    val rootAgent = DummyAgent("root", subAgents = listOf(subAgent))
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
