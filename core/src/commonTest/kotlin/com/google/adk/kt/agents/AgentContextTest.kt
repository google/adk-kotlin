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

import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextTest {

  @Test
  fun agent_instantiation_setsDefaultValues() {
    val agent = DummyAgent("test-agent")
    assertEquals("test-agent", agent.name)
    assertTrue(agent.subAgents.isEmpty())
  }

  @Test
  fun invocationContext_creation_setsDefaultValues() {
    val agent = DummyAgent("test-agent")
    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = agent,
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
      )

    assertEquals("test_session_id", context.session.key.id)
    assertEquals("test-agent", context.agent.name)
    assertEquals("invocation-id", context.invocationId)
  }

  @Test
  fun readonlyContext_creation_setsDefaultValues() {
    val agent = DummyAgent("test-agent")
    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = agent,
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
      )
    val readonlyContext = context.toReadonlyContext()

    assertEquals("test_session_id", readonlyContext.session.key.id)
    assertEquals("test-agent", readonlyContext.agentName)
  }

  @Test
  fun callbackContext_creation_setsDefaultValues() = runBlocking {
    val agent = DummyAgent("test-agent")
    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = agent,
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
      )
    val callbackContext = context.toCallbackContext()

    callbackContext.updateState("key", "value")
    assertEquals("value", callbackContext.state["key"])
    assertNotNull(callbackContext.eventActions.stateDelta["key"])
  }
}
