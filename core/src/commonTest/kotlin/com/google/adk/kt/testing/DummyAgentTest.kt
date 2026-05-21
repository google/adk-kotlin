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

package com.google.adk.kt.testing

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DummyAgentTest {

  @Test
  fun agent_instantiation_setsDefaultValues() {
    val agent = DummyAgent("test-agent")
    assertEquals("test-agent", agent.name)
    assertEquals("test", DummyAgent().name)
    assertTrue(agent.subAgents.isEmpty())
    assertTrue(agent.beforeAgentCallbacks.isEmpty())
    assertTrue(agent.afterAgentCallbacks.isEmpty())
  }

  @Test
  fun runAsync_executesOnRunAsync() = runBlocking {
    var asyncCalled = false
    val agent = DummyAgent(name = "test-agent", onRunAsync = { asyncCalled = true })

    val context =
      InvocationContext(
        session = testSession(),
        runConfig = null,
        agent = agent,
        userContent = Content(role = Role.USER),
        invocationId = "invocation-id",
      )

    val events = mutableListOf<Event>()
    agent.runAsync(context).toList(events)

    assertTrue(asyncCalled)
  }
}
