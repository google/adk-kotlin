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

package com.google.adk.kt.agents

import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ReadonlyContextImplTest {

  @Test
  fun readonlyContext_creation_setsDefaultValues() {
    val context =
      InvocationContext(
        runConfig = RunConfig(), // Add default RunConfig
        agent = DummyAgent("test-agent"), // Use local TestAgent
        session = testSession(),
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
        sessionService = InMemorySessionService(),
      )
    val readonlyContext = context.toReadonlyContext()

    assertEquals("test_session_id", readonlyContext.session.key.id)
    assertEquals("test-agent", readonlyContext.agentName)
  }

  @Test
  fun readonlyContext_session_isDefensiveCopy() {
    val context =
      InvocationContext(
        runConfig = RunConfig(),
        session = testSession(),
        agent = DummyAgent("agent"),
        invocationId = "test-id",
        // sessionService = InMemorySessionService(),
      )
    val readonlyContext = context.toReadonlyContext()

    assertNotSame(context.session, readonlyContext.session)
  }

  @Test
  fun readonlyContext_state_isDefensiveCopy() {
    val state = State(concurrentMutableMapOf<String, Any>().apply { put("key", "value") })
    val session =
      Session(
        key = SessionKey(appName = "app-name", userId = "user-id", id = "session-id"),
        state = state,
        events = mutableListOf(),
      )
    val context =
      InvocationContext(
        session = session,
        runConfig = RunConfig(),
        agent = DummyAgent("agent"),
        invocationId = "test-id",
        sessionService = InMemorySessionService(),
      )
    val readonlyContext = context.toReadonlyContext()
    val copyState = readonlyContext.state.toMutableMap()

    copyState["key"] = "new-value"
    assertEquals("value", context.session.state["key"])
  }

  @Test
  fun readonlyContext_userContent_isDefensiveCopy() {
    val userContent = userMessage("test")
    val context =
      InvocationContext(
        runConfig = RunConfig(),
        session = testSession(),
        agent = DummyAgent("agent"),
        userContent = userContent,
        invocationId = "test-id",
        sessionService = InMemorySessionService(),
      )
    val readonlyContext = context.toReadonlyContext()

    assertNotSame(context.userContent, readonlyContext.userContent)
    assertNotSame(context.userContent?.parts, readonlyContext.userContent?.parts)
  }
}
