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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class SequentialAgentTest {

  private val session = testSession()
  private val baseContext =
    InvocationContext(session = session, runConfig = null, agent = DummyAgent("root"))

  @Test
  fun testRunAsyncSequentialExecution() = runTest {
    val eventList = mutableListOf<String>()

    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          eventList.add("agent1_start")
          emit(createEvent("agent1", "msg1"))
          eventList.add("agent1_end")
        },
      )

    val agent2 =
      DummyAgent(
        "agent2",
        onRunAsync = {
          eventList.add("agent2_start")
          emit(createEvent("agent2", "msg2"))
          eventList.add("agent2_end")
        },
      )

    val sequentialAgent = SequentialAgent(name = "seq", subAgents = listOf(agent1, agent2))
    val events = sequentialAgent.runAsync(baseContext).toList()
    val nonStateEvents = events.filter { it.actions.stateDelta.isEmpty() }

    assertEquals(2, nonStateEvents.size)
    assertEquals("msg1", nonStateEvents[0].content?.parts?.get(0)?.text)
    assertEquals("msg2", nonStateEvents[1].content?.parts?.get(0)?.text)

    assertEquals(listOf("agent1_start", "agent1_end", "agent2_start", "agent2_end"), eventList)
  }

  @Test
  fun runAsync_resumingFromMiddle_startsFromCorrectAgent() = runTest {
    val eventList = mutableListOf<String>()

    val agent1 =
      DummyAgent(
        "agent1",
        onRunAsync = {
          eventList.add("agent1_start")
          emit(createEvent("agent1", "msg1"))
          eventList.add("agent1_end")
        },
      )

    val agent2 =
      DummyAgent(
        "agent2",
        onRunAsync = {
          eventList.add("agent2_start")
          emit(createEvent("agent2", "msg2"))
          eventList.add("agent2_end")
        },
      )

    val sequentialAgent = SequentialAgent(name = "seq", subAgents = listOf(agent1, agent2))

    val context =
      InvocationContext(
        session = session,
        runConfig = null,
        agent = DummyAgent("root"),
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )

    context.agentStates["seq"] =
      TypedData.MapValue(mapOf("current_sub_agent" to TypedData.StringValue("agent2")))

    val events = sequentialAgent.runAsync(context).toList()

    val nonStateEvents = events.filter { it.actions.agentState == null && !it.actions.endOfAgent }

    assertEquals(1, nonStateEvents.size)
    assertEquals("msg2", nonStateEvents[0].content?.parts?.get(0)?.text)

    assertEquals(listOf("agent2_start", "agent2_end"), eventList)
  }

  @Test
  fun testSequentialNotResumable_doesNotEmitEndOfAgent() = runTest {
    val agent1 = DummyAgent("agent1", onRunAsync = { emit(createEvent("agent1", "msg1")) })

    val sequentialAgent = SequentialAgent(name = "seq", subAgents = listOf(agent1))

    val context =
      InvocationContext(
        session = session,
        runConfig = null,
        agent = DummyAgent("root"),
        resumabilityConfig = ResumabilityConfig(isResumable = false),
      )

    val events = sequentialAgent.runAsync(context).toList()

    assertEquals(0, events.count { it.actions.endOfAgent })
  }

  private fun createEvent(author: String, text: String): Event {
    return Event(
      id = Uuid.random(),
      invocationId = "inv-1",
      author = author,
      content = userMessage(text),
    )
  }
}
