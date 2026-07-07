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

package com.google.adk.kt.a2a.agent

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import io.a2a.client.Client
import io.a2a.client.ClientEvent
import io.a2a.client.TaskEvent
import io.a2a.client.TaskUpdateEvent
import io.a2a.spec.AgentCapabilities
import io.a2a.spec.AgentCard
import io.a2a.spec.Artifact
import io.a2a.spec.DataPart
import io.a2a.spec.FilePart
import io.a2a.spec.FileWithUri
import io.a2a.spec.Message
import io.a2a.spec.Part as A2APart
import io.a2a.spec.Task
import io.a2a.spec.TaskArtifactUpdateEvent
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
class LegacyA2AAgentTest {

  private lateinit var mockClient: Client
  private lateinit var agentCard: AgentCard
  private lateinit var invocationContext: InvocationContext

  @Before
  fun setUp() {
    mockClient = mock()
    agentCard =
      AgentCard.Builder()
        .name("remote-agent")
        .description("Remote Agent")
        .url("http://example.com")
        .version("1.0.0")
        .protocolVersion("0.3.0")
        .defaultInputModes(listOf("text"))
        .defaultOutputModes(listOf("text"))
        .skills(listOf())
        .capabilities(AgentCapabilities.Builder().streaming(true).build())
        .build()

    whenever(mockClient.agentCard).thenReturn(agentCard)
    whenever(mockClient.cancelTask(any())).thenReturn(null)

    val mockAgent = DummyAgent()

    val session =
      Session(
        key = SessionKey(appName = "demo", userId = "user", id = "session-1"),
        events =
          mutableListOf(
            Event(invocationId = "invocation-0", author = "user", content = userMessage("hello"))
          ),
      )

    invocationContext = InvocationContext(agent = mockAgent, session = session, runConfig = null)
  }

  @Test
  fun createAgent_streaming_false_returnsNonStreamingAgent() {
    val agent = createTestAgent(streaming = false)
    assertThat(agent.isStreamingEnabled).isFalse()
  }

  @Test
  fun createAgent_streaming_true_returnsStreamingAgent() {
    val agent = createTestAgent()
    assertThat(agent.isStreamingEnabled).isTrue()
  }

  @Test
  fun runAsync_emptySession_shortCircuits() = runTest {
    val agent = createTestAgent()
    invocationContext.session.events.clear()

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(1)
    assertThat(events[0].turnComplete).isTrue()
    assertThat(events[0].content).isNull()

    val messageCaptor = argumentCaptor<Message>()
    verify(mockClient, never())
      .sendMessage(
        messageCaptor.capture(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )
  }

  @Test
  fun description_userDescriptionProvided_returnsUserDescription() {
    val agent =
      LegacyA2AAgent(
        name = "test-agent",
        userDescription = "Custom User Description",
        a2aClient = mockClient,
        agentCard = null,
      )
    assertThat(agent.description).isEqualTo("Custom User Description")
  }

  @Test
  fun description_userDescriptionNull_agentCardProvided_returnsAgentCardDescription() {
    val card = AgentCard.Builder(agentCard).description("Card Description").build()
    val agent =
      LegacyA2AAgent(
        name = "test-agent",
        userDescription = null,
        a2aClient = mockClient,
        agentCard = card,
      )
    assertThat(agent.description).isEqualTo("Card Description")
  }

  @Test
  fun description_userDescriptionNull_agentCardNull_resolvesFromClient() {
    val agent =
      LegacyA2AAgent(
        name = "test-agent",
        userDescription = null,
        a2aClient = mockClient,
        agentCard = null,
      )
    assertThat(agent.description).isEqualTo("Remote Agent")
  }

  @Test
  fun description_userDescriptionNull_andCardResolutionFails_throwsAgentCardResolutionError() {
    whenever(mockClient.agentCard).thenReturn(null)
    val agent =
      LegacyA2AAgent(
        name = "test-agent",
        userDescription = null,
        a2aClient = mockClient,
        agentCard = null,
      )
    val exception =
      org.junit.Assert.assertThrows(BaseRemoteA2AAgent.AgentCardResolutionError::class.java) {
        agent.description
      }
    assertThat(exception.message).contains("Failed to resolve agent card")
  }

  @Test
  fun runAsync_streamingDisabled_emitsEventsWithoutFinalAggregation() = runTest {
    val agent = createTestAgent(streaming = false)

    mockStreamResponse(this) { consumer ->
      consumer.accept(createTaskEvent(TaskState.COMPLETED, "Done"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    // Should contain the event from the stream, but no final aggregated event
    assertThat(events).hasSize(1)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Done")
    assertThat(events[0].partial).isFalse()
    assertThat(events[0].turnComplete).isTrue()
  }

  @Test
  fun runAsync_streamingEnabled_singleCompletedEvent_skipsAggregation() = runTest {
    val agent = createTestAgent()

    mockStreamResponse(this) { consumer ->
      consumer.accept(createTaskEvent(TaskState.COMPLETED, "Done"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    // Should contain the event from the stream
    assertThat(events).hasSize(1)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Done")
    assertThat(events[0].turnComplete).isTrue()
  }

  @Test
  fun runAsync_streamingEnabled_aggregatesPartialEvents() = runTest {
    val agent = createTestAgent()

    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("Hello ", true, false), agentCard)
      consumer.accept(createPartialEvent("world", true, true), agentCard)
      consumer.accept(createTaskEvent(TaskState.COMPLETED, "Final"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(4)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Hello ")
    assertThat(events[0].partial).isTrue()
    assertThat(events[0].turnComplete).isFalse()
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("world")
    assertThat(events[1].partial).isTrue()
    assertThat(events[1].turnComplete).isFalse()
    assertThat(events[2].content?.parts?.firstOrNull()?.text).isEqualTo("Hello world")
    assertThat(events[2].partial).isFalse()
    assertThat(events[2].turnComplete).isFalse()
    assertThat(events[3].content?.parts?.firstOrNull()?.text).isEqualTo("Final")
    assertThat(events[3].partial).isFalse()
    assertThat(events[3].turnComplete).isTrue()
  }

  @Test
  fun runAsync_aggregatesInterleavedFunctionCalls() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("Hello ", true, false), agentCard)
      consumer.accept(createPartialFunctionCallEvent("get_weather", "call_1"), agentCard)
      consumer.accept(createPartialEvent("World!", true, false), agentCard)
      consumer.accept(createFinalEvent("Final"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(5)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Hello ")
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("Hello ") // Aggregated
    assertThat(events[2].content?.parts?.firstOrNull()?.functionCall?.name).isEqualTo("get_weather")
    assertThat(events[3].content?.parts?.firstOrNull()?.text).isEqualTo("World!")
    assertThat(events[4].content?.parts?.firstOrNull()?.text).isEqualTo("Final")
  }

  @Test
  fun runAsync_aggregatesFiles() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("Here is a file: ", true, false), agentCard)
      consumer.accept(
        createPartialFileEvent("http://example.com/file.txt", "text/plain"),
        agentCard,
      )
      consumer.accept(createFinalEvent("Done"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(4)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Here is a file: ")
    assertThat(events[1].content?.parts?.firstOrNull()?.text)
      .isEqualTo("Here is a file: ") // Aggregated
    assertThat(events[2].content?.parts?.firstOrNull()?.fileData?.fileUri)
      .isEqualTo("http://example.com/file.txt")
    assertThat(events[3].content?.parts?.firstOrNull()?.text).isEqualTo("Done")
  }

  @Test
  fun runAsync_taskEventSnapshotResetsBuffer_emptyFinalEvent() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("Hello ", true, false), agentCard)
      consumer.accept(createPartialEvent("World!", true, false), agentCard)

      val task =
        Task.Builder()
          .id("task-1")
          .contextId("context-1")
          .status(TaskStatus(TaskState.COMPLETED))
          .build()
      consumer.accept(TaskEvent(task), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(3)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Hello ")
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("World!")
    assertThat(events[2].content).isNull()
    assertThat(events[2].turnComplete).isTrue()
  }

  @Test
  fun runAsync_taskStatusUpdateEventFlushesBuffer() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("Hello ", true, false), agentCard)
      consumer.accept(createPartialEvent("World!", true, false), agentCard)

      val status = TaskStatus(TaskState.COMPLETED)
      val update = TaskStatusUpdateEvent("task-1", status, "context-1", true, null)
      val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
      consumer.accept(TaskUpdateEvent(task, update), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(3)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Hello ")
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("World!")
    assertThat(events[2].content?.parts?.firstOrNull()?.text).isEqualTo("Hello World!")
    assertThat(events[2].turnComplete).isTrue()
  }

  @Test
  fun runAsync_taskEventSnapshotResetsBuffer_withContent() = runTest {
    val agent = createTestAgent()

    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("1", true, false), agentCard)
      consumer.accept(createPartialEvent("2", true, false), agentCard)
      consumer.accept(createPartialEvent("3", false, false), agentCard)
      consumer.accept(createPartialEvent("4", true, false), agentCard)
      consumer.accept(createFinalEvent("5"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(5)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("1")
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("2")
    assertThat(events[2].content?.parts?.firstOrNull()?.text).isEqualTo("3")
    assertThat(events[3].content?.parts?.firstOrNull()?.text).isEqualTo("4")
    assertThat(events[4].content?.parts?.firstOrNull()?.text).isEqualTo("5")
  }

  @Test
  fun runAsync_taskStatusUpdateEventFlushesBuffer_withContent() = runTest {
    val agent = createTestAgent()

    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("1", true, false), agentCard)
      consumer.accept(createPartialEvent("2", true, false), agentCard)
      consumer.accept(createPartialEvent("3", false, false), agentCard)
      consumer.accept(createPartialEvent("4", true, false), agentCard)

      val status = TaskStatus(TaskState.COMPLETED)
      val update = TaskStatusUpdateEvent("task-1", status, "context-1", true, null)
      val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
      consumer.accept(TaskUpdateEvent(task, update), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(5)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("1")
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("2")
    assertThat(events[2].content?.parts?.firstOrNull()?.text).isEqualTo("3")
    assertThat(events[3].content?.parts?.firstOrNull()?.text).isEqualTo("4")
    assertThat(events[4].content?.parts?.firstOrNull()?.text).isEqualTo("34")
    assertThat(events[4].turnComplete).isTrue()
  }

  @Test
  fun runAsync_handlesTasksWithStatusMessage() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(createTaskEvent(TaskState.COMPLETED, "hello"), agentCard)
    }
    val events = agent.runAsync(invocationContext).toList()
    assertThat(events).hasSize(1)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("hello")
  }

  @Test
  fun runAsync_handlesTasksWithMultipartArtifact() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(
        createTestEvent(
          listOf(TextPart("hello"), TextPart("world")),
          TaskState.COMPLETED,
          append = false,
          lastChunk = false,
        ),
        agentCard,
      )
    }
    val events = agent.runAsync(invocationContext).toList()
    assertThat(events).hasSize(1)
    val parts = events[0].content?.parts
    assertThat(parts).hasSize(2)
    assertThat(parts?.get(0)?.text).isEqualTo("hello")
    assertThat(parts?.get(1)?.text).isEqualTo("world")
  }

  @Test
  fun runAsync_handlesNonFinalStatusUpdatesAsThoughts() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(createStatusUpdateEvent(TaskState.SUBMITTED, "submitted..."), agentCard)
      consumer.accept(createStatusUpdateEvent(TaskState.WORKING, "working..."), agentCard)
      consumer.accept(createFinalEvent("done"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(3)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("submitted...")
    assertThat(events[0].content?.parts?.firstOrNull()?.thought).isEqualTo(true)
    assertThat(events[1].content?.parts?.firstOrNull()?.text).isEqualTo("working...")
    assertThat(events[1].content?.parts?.firstOrNull()?.thought).isEqualTo(true)
    assertThat(events[2].content?.parts?.firstOrNull()?.text).isEqualTo("done")
    assertThat(events[2].content?.parts?.firstOrNull()?.thought).isNotEqualTo(true)
  }

  @Test
  fun runAsync_constructsRequestWithHistory() = runTest {
    val agent = createTestAgent()
    val historySession =
      Session(
        key = SessionKey(appName = "demo", userId = "user", id = "session-2"),
        events =
          mutableListOf(
            Event(invocationId = "invocation-1", author = "user", content = userMessage("hello")),
            Event(invocationId = "invocation-1", author = "model", content = modelMessage("hi")),
            Event(
              invocationId = "invocation-1",
              author = "user",
              content = userMessage("how are you?"),
            ),
          ),
      )
    val context = invocationContext.copy(session = historySession)
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("fine"), agentCard) }

    agent.runAsync(context).toList()

    val messageCaptor = argumentCaptor<Message>()
    verify(mockClient)
      .sendMessage(
        messageCaptor.capture(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )

    val message = messageCaptor.firstValue
    assertThat(message.role).isEqualTo(Message.Role.USER)
    assertThat(message.parts).hasSize(4)
    assertThat((message.parts[0] as TextPart).text).isEqualTo("hello")
    assertThat((message.parts[1] as TextPart).text).isEqualTo("For context:")
    assertThat((message.parts[2] as TextPart).text).isEqualTo("[model] said: hi")
    assertThat((message.parts[3] as TextPart).text).isEqualTo("how are you?")
  }

  @Test
  fun runAsync_constructsRequestWithFunctionResponse() = runTest {
    val agent = createTestAgent()
    val sessionWithFR =
      Session(
        key = SessionKey(appName = "demo", userId = "user", id = "session-3"),
        events =
          mutableListOf(
            Event(
              invocationId = "invocation-1",
              author = "user",
              content =
                Content(
                  role = "user",
                  parts =
                    listOf(
                      Part(
                        functionResponse =
                          FunctionResponse(
                            name = "fn",
                            id = "call-1",
                            response = mapOf("status" to "ok"),
                          )
                      )
                    ),
                ),
            )
          ),
      )
    val context = invocationContext.copy(session = sessionWithFR)
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("ok"), agentCard) }

    agent.runAsync(context).toList()

    val messageCaptor = argumentCaptor<Message>()
    verify(mockClient)
      .sendMessage(
        messageCaptor.capture(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )

    val message = messageCaptor.firstValue
    assertThat(message.parts).hasSize(1)
    val part = message.parts[0]
    assertThat(part).isInstanceOf(DataPart::class.java)
    val dataPart = part as DataPart
    assertThat(dataPart.data["name"]).isEqualTo("fn")
    assertThat(dataPart.data["id"]).isEqualTo("call-1")
    assertThat(dataPart.metadata["adk_type"]).isEqualTo("function_response")
  }

  @Test
  fun runAsync_handlesClientError() = runTest {
    val agent = createTestAgent()

    val error = RuntimeException("Connection failed")
    mockStreamError(this, error)

    val result = runCatching { agent.runAsync(invocationContext).toList() }
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()?.message).contains("Connection failed")
  }

  @Test
  fun runAsync_invokesBeforeAndAfterCallbacks() = runTest {
    var beforeCalled = false
    var afterCalled = false
    val agent =
      createTestAgent(
        beforeCallbacks =
          listOf(
            BeforeAgentCallback { _ ->
              beforeCalled = true
              CallbackChoice.Continue(EventActions())
            }
          ),
        afterCallbacks =
          listOf(
            AfterAgentCallback { _ ->
              afterCalled = true
              CallbackChoice.Continue(Unit)
            }
          ),
      )
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("done"), agentCard) }

    agent.runAsync(invocationContext).toList()

    assertThat(beforeCalled).isTrue()
    assertThat(afterCalled).isTrue()
  }

  @Test
  fun runAsync_beforeCallbackCanShortCircuit() = runTest {
    val shortCircuitContent = modelMessage("short circuit")
    val agent =
      createTestAgent(
        beforeCallbacks =
          listOf(BeforeAgentCallback { _ -> CallbackChoice.Break(shortCircuitContent) })
      )

    val events = agent.runAsync(invocationContext).toList()

    assertThat(events).hasSize(1)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("short circuit")

    verifyNoInteractions(mockClient)
  }

  private fun createTestAgent(
    streaming: Boolean = true,
    beforeCallbacks: List<BeforeAgentCallback> = emptyList(),
    afterCallbacks: List<AfterAgentCallback> = emptyList(),
  ): BaseRemoteA2AAgent {
    return JvmA2AAgent(
      name = "remote-agent",
      client = mockClient,
      agentCard = agentCard,
      streaming = streaming,
      beforeAgentCallbacks = beforeCallbacks,
      afterAgentCallbacks = afterCallbacks,
    )
  }

  private fun assertEventText(event: Event?, expectedText: String) {
    assertThat(event?.content?.parts?.firstOrNull()?.text).isEqualTo(expectedText)
  }

  private fun mockStreamError(scope: CoroutineScope, error: Throwable) {
    doAnswer { invocation ->
        val errorConsumer = invocation.getArgument<Consumer<Throwable>>(2)
        scope.launch {
          delay(10)
          errorConsumer.accept(error)
        }
        null
      }
      .whenever(mockClient)
      .sendMessage(
        any<Message>(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )
  }

  private fun mockStreamResponse(
    scope: CoroutineScope,
    responseProducer: (BiConsumer<ClientEvent, AgentCard>) -> Unit,
  ) {
    doAnswer { invocation ->
        val consumers = invocation.getArgument<List<BiConsumer<ClientEvent, AgentCard>>>(1)
        val consumer = consumers[0]
        scope.launch {
          delay(10)
          responseProducer(consumer)
        }
        null
      }
      .whenever(mockClient)
      .sendMessage(
        any<Message>(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )
  }

  private fun createTaskEvent(state: TaskState, text: String): TaskEvent {
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(
          TaskStatus(
            state,
            Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart(text))).build(),
            null,
          )
        )
        .build()
    return TaskEvent(task)
  }

  private fun createStatusUpdateEvent(state: TaskState, text: String): ClientEvent {
    val task = Task.Builder().id("task-1").contextId("context-1").status(TaskStatus(state)).build()
    val update =
      TaskStatusUpdateEvent.Builder()
        .taskId("task-1")
        .contextId("context-1")
        .status(
          TaskStatus(
            state,
            Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart(text))).build(),
            null,
          )
        )
        .build()
    return TaskUpdateEvent(task, update)
  }

  private fun createPartialEvent(text: String, append: Boolean, lastChunk: Boolean): ClientEvent {
    return createTestEvent(TextPart(text), TaskState.WORKING, append, lastChunk)
  }

  private fun createPartialFunctionCallEvent(name: String, id: String): ClientEvent {
    val data = mapOf("name" to name, "id" to id, "args" to mapOf<String, Any>())
    val metadata = mapOf("adk_type" to "function_call")
    return createTestEvent(DataPart(data, metadata), TaskState.WORKING, true, false)
  }

  private fun createPartialFileEvent(uri: String, mimeType: String): ClientEvent {
    return createTestEvent(
      FilePart(FileWithUri(mimeType, "file", uri)),
      TaskState.WORKING,
      true,
      false,
    )
  }

  private fun createFinalEvent(text: String): ClientEvent {
    return createTestEvent(TextPart(text), TaskState.COMPLETED, false, false)
  }

  private fun createTestEvent(
    part: A2APart<*>,
    state: TaskState,
    append: Boolean,
    lastChunk: Boolean,
  ): ClientEvent = createTestEvent(listOf(part), state, append, lastChunk)

  private fun createTestEvent(
    parts: List<A2APart<*>>,
    state: TaskState,
    append: Boolean,
    lastChunk: Boolean,
  ): ClientEvent {
    val artifact = Artifact.Builder().artifactId("artifact-1").parts(parts).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(state))
        .artifacts(listOf(artifact))
        .build()

    if (state == TaskState.COMPLETED && !append && !lastChunk) {
      return TaskEvent(task)
    }

    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .lastChunk(lastChunk)
        .append(append)
        .contextId("context-1")
        .artifact(artifact)
        .taskId("task-id-1")
        .build()
    return TaskUpdateEvent(task, updateEvent)
  }
}
