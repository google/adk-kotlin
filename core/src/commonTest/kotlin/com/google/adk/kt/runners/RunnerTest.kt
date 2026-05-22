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

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class RunnerTest {

  // Mock Tools
  class GetWeatherTool : BaseTool("get_weather", "Get current weather") {
    override fun declaration(): FunctionDeclaration {
      return FunctionDeclaration(name = "get_weather", description = "Get weather")
    }

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
      return mapOf("weather" to "sunny", "temperature" to 25)
    }
  }

  class GetCurrentTimeTool : BaseTool("get_current_time", "Get current time") {
    override fun declaration(): FunctionDeclaration {
      return FunctionDeclaration(name = "get_current_time", description = "Get time")
    }

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
      return mapOf("time" to "12:00 PM")
    }
  }

  @Test
  fun runAsync_withTools_executesTools() = runTest {
    val weatherTool = GetWeatherTool()
    val timeTool = GetCurrentTimeTool()
    val mockModel =
      DummyModel("mock-model") { request ->
        flow {
          val lastContent = request.contents.lastOrNull()

          // Logic to decide response based on conversation history
          // If the last message is from user, call tools.
          // If the last message contains function responses, return final answer.

          val isFunctionResponse = lastContent?.parts?.any { it.functionResponse != null } == true

          if (!isFunctionResponse) {
            // Return Function Calls
            emit(
              modelParallelFunctionCallsResponse(
                FunctionCall(name = "get_weather", args = emptyMap()),
                FunctionCall(name = "get_current_time", args = emptyMap()),
              )
            )
          } else {
            // Return Final Answer
            emit(LlmResponse(content = modelMessage("The weather is sunny and it is 12:00 PM.")))
          }
        }
      }

    val agent =
      LlmAgent(name = "test-agent", model = mockModel, tools = listOf(weatherTool, timeTool))

    val runner = InMemoryRunner(agent = agent)

    val userMessage = userMessage("What is the weather and time?")

    runner.runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage).toList()

    val session = runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
    assertNotNull(session)
    val allEvents = session.events

    // Assert the exact order of events in the session
    assertEquals(4, allEvents.size)

    // 1. User Request
    val event1 = allEvents[0]
    assertEquals(Role.USER, event1.author)
    assertEquals("What is the weather and time?", event1.content?.parts?.get(0)?.text)

    // 2. Model Function Calls
    // The model returns two function calls in a single event.
    val event2 = allEvents[1]
    assertEquals("test-agent", event2.author)
    assertEquals(2, event2.functionCalls().size)
    val callNames = event2.functionCalls().map { it.name }
    assertTrue(callNames.containsAll(listOf("get_weather", "get_current_time")))

    // 3. Function Responses
    // The tool responses are merged into a single event.
    val event3 = allEvents[2]
    assertEquals("test-agent", event3.author)
    assertEquals(2, event3.functionResponses().size)
    val responseNames = event3.functionResponses().map { it.name }
    assertTrue(responseNames.containsAll(listOf("get_weather", "get_current_time")))

    // 4. Final Response
    val event4 = allEvents[3]
    assertEquals("test-agent", event4.author)
    assertEquals(
      "The weather is sunny and it is 12:00 PM.",
      event4.content?.parts?.get(0)?.text ?: "",
    )
  }

  @Test
  fun runAsync_withCustomPluginManager_passesToContext() = runTest {
    var capturedPluginManager: PluginManager? = null
    val spyAgent =
      DummyAgent(
        name = "spy-agent",
        onRunAsync = { ctx -> capturedPluginManager = ctx.pluginManager },
      )

    val customPluginManager = PluginManager()
    val runner = InMemoryRunner(agent = spyAgent, pluginManager = customPluginManager)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = Content(Role.USER))
      .toList()

    assertEquals(customPluginManager, capturedPluginManager)
  }

  @Test
  fun runAsync_withOnUserMessageCallback_modifiesMessage() = runTest {
    val spyAgent = DummyAgent(name = "spy-agent")

    val plugin =
      object : Plugin {
        override val name = "test-plugin"

        override suspend fun onUserMessage(
          invocationContext: InvocationContext,
          userMessage: Content,
        ): Content {
          return userMessage("Modified user message")
        }
      }

    val runner = InMemoryRunner(agent = spyAgent, pluginManager = PluginManager(listOf(plugin)))

    val originalMessage = userMessage("Original message")
    runner.runAsync(userId = "user1", sessionId = "session1", newMessage = originalMessage).toList()

    val session = runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
    assertNotNull(session)
    val events = session.events
    assertEquals(1, events.size)
    assertEquals(Role.USER, events[0].author)
    assertEquals("Modified user message", events[0].content?.parts?.get(0)?.text)
  }

  @Test
  fun runAsync_withBeforeRunShortCircuit_shortCircuits() = runTest {
    var agentRan = false
    val spyAgent = DummyAgent(name = "spy-agent", onRunAsync = { agentRan = true })

    val plugin =
      object : Plugin {
        override val name = "test-plugin"

        override suspend fun beforeRun(
          invocationContext: InvocationContext
        ): CallbackChoice<Unit, Content> {
          return CallbackChoice.Break(modelMessage("Short-circuited!"))
        }
      }

    val runner = InMemoryRunner(agent = spyAgent, pluginManager = PluginManager(listOf(plugin)))
    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = Content(Role.USER))
        .toList()

    assertEquals(1, events.size)
    assertEquals(Role.MODEL, events[0].author)
    assertEquals("Short-circuited!", events[0].content?.parts?.get(0)?.text)
    assertFalse(agentRan)
  }

  @Test
  fun runAsync_withOnEventCallback_modifiesEvent() = runTest {
    val spyAgent =
      DummyAgent(
        name = "spy-agent",
        onRunAsync = { ctx ->
          emit(
            Event(
              invocationId = ctx.invocationId,
              author = Role.MODEL,
              content = modelMessage("Original model response"),
            )
          )
        },
      )

    val plugin =
      object : Plugin {
        override val name = "test-plugin"

        override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
          return if (event.author == Role.MODEL) {
            event.copy(content = modelMessage("Modified model response"))
          } else {
            event
          }
        }
      }

    val runner = InMemoryRunner(agent = spyAgent, pluginManager = PluginManager(listOf(plugin)))
    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = Content(Role.USER))
        .toList()

    assertEquals(1, events.size)
    assertEquals(Role.MODEL, events[0].author)
    assertEquals("Modified model response", events[0].content?.parts?.get(0)?.text)
  }

  @Test
  fun runAsync_withAfterRunCallback_executes() = runTest {
    var afterRunCalled = false
    val spyAgent =
      DummyAgent(
        name = "spy-agent",
        onRunAsync = { ctx ->
          emit(
            Event(
              invocationId = ctx.invocationId,
              author = Role.MODEL,
              content = modelMessage("Done"),
            )
          )
        },
      )

    val plugin =
      object : Plugin {
        override val name = "test-plugin"

        override suspend fun afterRun(invocationContext: InvocationContext) {
          afterRunCalled = true
        }
      }

    val runner = InMemoryRunner(agent = spyAgent, pluginManager = PluginManager(listOf(plugin)))
    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = Content(Role.USER))
      .toList()

    assertTrue(afterRunCalled)
  }

  @Test
  fun runAsync_withAgentTransfer_transfersCorrectly() = runTest {
    val subModel =
      DummyModel("sub-model") {
        flow { emit(LlmResponse(content = modelMessage("Hello from sub-agent!"))) }
      }
    val rootModel =
      DummyModel("root-model") { request ->
        flow {
          val lastContent = request.contents.lastOrNull()
          val isFunctionResponse = lastContent?.parts?.any { it.functionResponse != null } == true

          if (!isFunctionResponse) {
            // Emulate root agent deciding to transfer to sub-agent
            emit(modelTransferToAgentResponse("sub"))
          } else {
            // Ideally, after transfer, root agent isn't called again for this turn,
            // but if it is, return empty or generic response
            emit(LlmResponse(content = modelMessage("Root agent finished.")))
          }
        }
      }
    val subAgent = LlmAgent(name = "sub", description = "Sub agent.", model = subModel)
    val rootAgent =
      LlmAgent(
        name = "root",
        description = "Root agent.",
        model = rootModel,
        subAgents = listOf(subAgent),
      )
    val runner = InMemoryRunner(agent = rootAgent)

    val userMessage = userMessage("Talk to sub agent.")
    val events =
      runner.runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage).toList()

    // Verify flow events
    val flowEvents = events.filter { it.author != Role.USER }
    assertEquals(3, flowEvents.size)
    // 1. root agent -> function call transfer_to_agent
    assertEquals("root", flowEvents[0].author)
    assertEquals(TRANSFER_TO_AGENT_TOOL_NAME, flowEvents[0].functionCalls()[0].name)
    // 2. root agent -> function response (from ADK)
    assertEquals("root", flowEvents[1].author)
    assertEquals(TRANSFER_TO_AGENT_TOOL_NAME, flowEvents[1].functionResponses()[0].name)
    // 3. sub agent -> text response
    assertEquals("sub", flowEvents[2].author)
    assertEquals("Hello from sub-agent!", flowEvents[2].content?.parts?.get(0)?.text)
    // Verify session events
    val session = runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
    assertNotNull(session)
    val allEvents = session.events
    assertEquals(4, allEvents.size)
    assertEquals(Role.USER, allEvents[0].author)
    assertEquals("Talk to sub agent.", allEvents[0].content?.parts?.get(0)?.text)
    assertEquals("root", allEvents[1].author)
    assertEquals(TRANSFER_TO_AGENT_TOOL_NAME, allEvents[1].functionCalls()[0].name)
    assertEquals("root", allEvents[2].author)
    assertEquals(TRANSFER_TO_AGENT_TOOL_NAME, allEvents[2].functionResponses()[0].name)
    assertEquals("sub", allEvents[3].author)
    assertEquals("Hello from sub-agent!", allEvents[3].content?.parts?.get(0)?.text)
  }

  @Test
  fun run_withValidMessage_executesAndReturnsEvents() = runTest {
    val spyAgent =
      DummyAgent(
        name = "spy-agent",
        onRunAsync = { emit(Event(author = Role.MODEL, content = modelMessage("OK"))) },
      )
    val runner = InMemoryRunner(agent = spyAgent)
    val userMessage = userMessage("hi")

    val events = runner.run(userId = "user1", sessionId = "session1", newMessage = userMessage)

    val eventList = events.asSequence().toList()
    assertEquals(1, eventList.size)
    assertEquals("OK", eventList[0].content?.parts?.get(0)?.text)
  }

  @Test
  fun runAsync_withNullMessageAndNotResumable_throwsException() = runTest {
    val spyAgent = DummyAgent(name = "spy-agent")
    val runner = InMemoryRunner(agent = spyAgent)

    try {
      runner.runAsync(userId = "user1", sessionId = "session1", newMessage = null).toList()
      assertTrue(false, "Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertEquals("No new message provided and session is not resumable", e.message)
    }
  }
}
