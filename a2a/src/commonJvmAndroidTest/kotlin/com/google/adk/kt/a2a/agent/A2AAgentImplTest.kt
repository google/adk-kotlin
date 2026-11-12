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

import com.google.adk.kt.a2a.converters.MetadataKeys
import com.google.adk.kt.a2a.converters.TYPE_FUNCTION_RESPONSE
import com.google.adk.kt.a2a.converters.contextId
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolResponse
import com.google.common.truth.Truth.assertThat
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.ClientEvent
import org.a2aproject.sdk.client.MessageEvent
import org.a2aproject.sdk.client.TaskEvent
import org.a2aproject.sdk.client.TaskUpdateEvent
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.Artifact
import org.a2aproject.sdk.spec.DataPart
import org.a2aproject.sdk.spec.FilePart
import org.a2aproject.sdk.spec.FileWithUri
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Part as A2APart
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TaskStatus
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent
import org.a2aproject.sdk.spec.TextPart
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.after
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
class A2AAgentImplTest {

  private lateinit var mockClient: Client
  private lateinit var agentCard: AgentCard
  private lateinit var invocationContext: InvocationContext

  // Records the streaming flag the agent passed to its client provider on the last invocation.
  private var capturedStreaming: Boolean? = null

  @Before
  fun setUp() {
    mockClient = mock()
    agentCard =
      AgentCard.builder()
        .name("remote-agent")
        .description("Remote Agent")
        .url("http://example.com")
        .version("1.0.0")
        .defaultInputModes(listOf("text"))
        .defaultOutputModes(listOf("text"))
        .skills(listOf())
        .supportedInterfaces(listOf())
        .capabilities(AgentCapabilities.builder().streaming(true).build())
        .build()

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

    // Streams by default: most tests deliver events after sendMessage returns, as a stream does.
    invocationContext =
      InvocationContext(
        agent = mockAgent,
        session = session,
        runConfig = RunConfig(streamingMode = StreamingMode.SSE),
      )
  }

  @Test
  fun isStreamingEnabled_cardAdvertisesStreaming_returnsTrue() {
    // The default card advertises streaming capability.
    val agent = createTestAgent()
    assertThat(agent.isStreamingEnabled).isTrue()
  }

  @Test
  fun isStreamingEnabled_cardWithoutStreamingCapability_returnsFalse() {
    agentCard =
      AgentCard.builder(agentCard)
        .capabilities(AgentCapabilities.builder().streaming(false).build())
        .build()
    val agent = createTestAgent()
    assertThat(agent.isStreamingEnabled).isFalse()
  }

  @Test
  fun isStreamingEnabled_transportDoesNotSupportStreaming_returnsFalse() {
    val agent = createTestAgent(transportSupportsStreaming = false)
    assertThat(agent.isStreamingEnabled).isFalse()
  }

  @Test
  fun runAsync_runConfigSse_buildsStreamingClient() = runTest {
    val agent = createTestAgent()
    val context = invocationContext.copy(runConfig = RunConfig(streamingMode = StreamingMode.SSE))
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("done"), agentCard) }

    agent.runAsync(context).toList()

    assertThat(capturedStreaming).isTrue()
  }

  @Test
  fun runAsync_runConfigNone_buildsNonStreamingClient() = runTest {
    val agent = createTestAgent()
    val context = invocationContext.copy(runConfig = RunConfig(streamingMode = StreamingMode.NONE))
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("done"), agentCard) }

    agent.runAsync(context).toList()

    assertThat(capturedStreaming).isFalse()
  }

  @Test
  fun runAsync_runConfigSseButCardNotStreamingCapable_buildsNonStreamingClient() = runTest {
    agentCard =
      AgentCard.builder(agentCard)
        .capabilities(AgentCapabilities.builder().streaming(false).build())
        .build()
    val agent = createTestAgent()
    val context = invocationContext.copy(runConfig = RunConfig(streamingMode = StreamingMode.SSE))
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("done"), agentCard) }

    agent.runAsync(context).toList()

    assertThat(capturedStreaming).isFalse()
  }

  @Test
  fun runAsync_runConfigSseButTransportCannotStream_buildsNonStreamingClient() = runTest {
    val agent = createTestAgent(transportSupportsStreaming = false)
    val context = invocationContext.copy(runConfig = RunConfig(streamingMode = StreamingMode.SSE))
    mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("done"), agentCard) }

    agent.runAsync(context).toList()

    assertThat(capturedStreaming).isFalse()
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
      A2AAgentImpl(
        name = "test-agent",
        userDescription = "Custom User Description",
        clientProvider = { mockClient },
        agentCard = agentCard,
        transportSupportsStreaming = true,
      )
    assertThat(agent.description).isEqualTo("Custom User Description")
  }

  @Test
  fun description_userDescriptionNull_agentCardProvided_returnsAgentCardDescription() {
    val card = AgentCard.builder(agentCard).description("Card Description").build()
    val agent =
      A2AAgentImpl(
        name = "test-agent",
        userDescription = null,
        clientProvider = { mockClient },
        agentCard = card,
        transportSupportsStreaming = true,
      )
    assertThat(agent.description).isEqualTo("Card Description")
  }

  @Test
  fun runAsync_streamingDisabled_emitsEventsWithoutFinalAggregation() = runTest {
    val agent = createTestAgent()

    mockStreamResponse(this) { consumer ->
      consumer.accept(createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Done"), agentCard)
    }

    val events = agent.runAsync(blockingContext()).toList()

    assertThat(capturedStreaming).isFalse()
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
      consumer.accept(createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Done"), agentCard)
    }

    val events = agent.runAsync(invocationContext).toList()

    // Should contain the event from the stream
    assertThat(events).hasSize(1)
    assertThat(events[0].content?.parts?.firstOrNull()?.text).isEqualTo("Done")
    assertThat(events[0].turnComplete).isTrue()
  }

  @Test
  fun runAsync_recordsRequestAndResponseAsObjects() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      mockStreamResponse(this) { consumer ->
        consumer.accept(createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Done"), agentCard)
      }

      val event = agent.runAsync(invocationContext).toList().single()

      val response = assertIs<Map<*, *>>(event.customMetadata?.get("a2a:response"))
      assertThat(response["id"]).isEqualTo("task-1")
      assertThat(assertIs<Map<*, *>>(response["status"])["state"]).isEqualTo("TASK_STATE_COMPLETED")
      val request = assertIs<Map<*, *>>(event.customMetadata?.get("a2a:request"))
      assertThat(request["role"]).isEqualTo("ROLE_USER")
    }

  @Test
  fun runAsync_streamingEnabled_aggregatesPartialEvents() = runTest {
    val agent = createTestAgent()

    mockStreamResponse(this) { consumer ->
      consumer.accept(createPartialEvent("Hello ", true, false), agentCard)
      consumer.accept(createPartialEvent("world", true, true), agentCard)
      consumer.accept(createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Final"), agentCard)
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
    assertThat(events[2].customMetadata).containsKey("a2a:response")
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
        Task.builder()
          .id("task-1")
          .contextId("context-1")
          .status(TaskStatus(TaskState.TASK_STATE_COMPLETED))
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

      val status = TaskStatus(TaskState.TASK_STATE_COMPLETED)
      val update = TaskStatusUpdateEvent("task-1", status, "context-1", null)
      val task = Task.builder().id("task-1").contextId("context-1").status(status).build()
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

      val status = TaskStatus(TaskState.TASK_STATE_COMPLETED)
      val update = TaskStatusUpdateEvent("task-1", status, "context-1", null)
      val task = Task.builder().id("task-1").contextId("context-1").status(status).build()
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
      consumer.accept(createTaskEvent(TaskState.TASK_STATE_COMPLETED, "hello"), agentCard)
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
          TaskState.TASK_STATE_COMPLETED,
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
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_SUBMITTED, "submitted..."),
        agentCard,
      )
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_WORKING, "working..."),
        agentCard,
      )
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
    assertThat(message.role).isEqualTo(Message.Role.ROLE_USER)
    assertThat(message.parts).hasSize(4)
    assertThat((message.parts[0] as TextPart).text).isEqualTo("hello")
    assertThat((message.parts[1] as TextPart).text).isEqualTo("For context:")
    assertThat((message.parts[2] as TextPart).text).isEqualTo("[model] said: hi")
    assertThat((message.parts[3] as TextPart).text).isEqualTo("how are you?")
  }

  @Test
  fun runAsync_otherAgentToolAndSignatureParts_leavesThemOutOfRequest() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      val searchParts =
        listOf(
          Part(toolCall = ToolCall(id = "t1")),
          Part(toolResponse = ToolResponse(id = "t1")),
          Part(text = "found it"),
          Part(thoughtSignature = byteArrayOf(1)),
        )
      val session =
        Session(
          key = SessionKey(appName = "demo", userId = "user", id = "session-2"),
          events =
            mutableListOf(
              Event(
                invocationId = "invocation-1",
                author = "search_agent",
                content = Content(role = Role.MODEL, parts = searchParts),
              ),
              Event(invocationId = "invocation-1", author = "user", content = userMessage("thanks")),
            ),
        )
      mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("ok"), agentCard) }

      agent.runAsync(invocationContext.copy(session = session)).toList()

      val messageCaptor = argumentCaptor<Message>()
      verify(mockClient)
        .sendMessage(
          messageCaptor.capture(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )
      assertThat(messageCaptor.firstValue.parts.map { (it as TextPart).text })
        .containsExactly("For context:", "[search_agent] said: found it", "thanks")
        .inOrder()
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
                userFunctionResponse(name = "fn", id = "call-1", response = mapOf("status" to "ok")),
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
    val data = dataPart.data as Map<*, *>
    assertThat(data["name"]).isEqualTo("fn")
    assertThat(data["id"]).isEqualTo("call-1")
    assertThat(dataPart.metadata?.get("adk_type")).isEqualTo("function_response")
  }

  @Test
  fun runAsync_otherAgentCredentialRequest_leavesItOutOfRequest() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      val authConfig =
        mapOf("rawAuthCredential" to mapOf("oauth2" to mapOf("clientSecret" to "s3cr3t")))
      val credentialCall =
        FunctionCall(name = "adk_request_credential", args = mapOf("authConfig" to authConfig))
      val session =
        Session(
          key = SessionKey(appName = "demo", userId = "user", id = "session-2"),
          events =
            mutableListOf(
              Event(
                invocationId = "invocation-1",
                author = "auth_agent",
                content =
                  Content(
                    role = Role.MODEL,
                    parts =
                      listOf(Part(text = "Please sign in."), Part(functionCall = credentialCall)),
                  ),
              ),
              Event(invocationId = "invocation-1", author = "user", content = userMessage("thanks")),
            ),
        )
      mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("ok"), agentCard) }

      agent.runAsync(invocationContext.copy(session = session)).toList()

      val messageCaptor = argumentCaptor<Message>()
      verify(mockClient)
        .sendMessage(
          messageCaptor.capture(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )
      assertThat(messageCaptor.firstValue.parts.map { (it as TextPart).text })
        .containsExactly("For context:", "[auth_agent] said: Please sign in.", "thanks")
        .inOrder()
    }

  @Test
  fun runAsync_onlyCredentialAnswer_skipsCallAndKeepsContextId() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      val credentialCall = FunctionCall(name = "adk_request_credential", id = "cred-id")
      val session =
        Session(
          key = SessionKey(appName = "demo", userId = "user", id = "session-4"),
          events =
            mutableListOf(
              Event(
                invocationId = "invocation-1",
                author = "remote-agent",
                content = modelMessage(Part(functionCall = credentialCall)),
                customMetadata = mapOf("a2a:context_id" to "context-1"),
              ),
              Event(
                invocationId = "invocation-1",
                author = "user",
                content =
                  userFunctionResponse(
                    name = "adk_request_credential",
                    id = "cred-id",
                    response = mapOf("token" to "t"),
                  ),
              ),
            ),
        )

      val events =
        withTimeout(5.seconds) {
          agent.runAsync(invocationContext.copy(session = session)).toList()
        }

      assertThat(events.single().contextId).isEqualTo("context-1")
      verify(mockClient, never())
        .sendMessage(
          any<Message>(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )
    }

  @Test
  fun runAsync_streamFails_yieldsErrorEventWithRequestThenFails() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      mockStreamError(this, RuntimeException("Connection failed"))
      val events = mutableListOf<Event>()

      val error =
        assertFailsWith<RuntimeException> {
          withTimeout(5.seconds) { agent.runAsync(invocationContext).toList(events) }
        }

      assertThat(error).hasMessageThat().contains("Connection failed")
      val errorEvent = events.single()
      assertThat(errorEvent.errorMessage).isEqualTo("A2A request failed (RuntimeException)")
      assertThat(errorEvent.customMetadata?.get("a2a:request")).isInstanceOf(Map::class.java)
    }

  @Test
  fun runAsync_streamFailsAfterResponse_yieldsResponseThenErrorEvent() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      val working = createStatusUpdateEvent(TaskState.TASK_STATE_WORKING, "working...")
      mockStreamError(this, RuntimeException("Connection reset"), responses = listOf(working))
      val events = mutableListOf<Event>()

      val error =
        assertFailsWith<RuntimeException> {
          withTimeout(5.seconds) { agent.runAsync(invocationContext).toList(events) }
        }

      assertThat(error).hasMessageThat().contains("Connection reset")
      assertThat(events.map { it.content?.parts?.single()?.text ?: it.errorMessage })
        .containsExactly("working...", "A2A request failed (RuntimeException)")
        .inOrder()
      assertThat(events.last().customMetadata).containsKey("a2a:request")
    }

  @Test
  fun runAsync_sendMessageCancelled_yieldsNoErrorEvent() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      doThrow(CancellationException("send cancelled"))
        .whenever(mockClient)
        .sendMessage(
          any<Message>(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )
      val events = mutableListOf<Event>()

      val error =
        assertFailsWith<CancellationException> {
          withTimeout(5.seconds) { agent.runAsync(invocationContext).toList(events) }
        }

      assertThat(error).hasMessageThat().isEqualTo("send cancelled")
      assertThat(events).isEmpty()
    }

  @Test
  fun runAsync_sendMessageThrows_closesClientAndPropagates() = runTest {
    // sendMessage can throw before awaitClose is registered; the client must still be closed (no
    // leak) and the error must propagate.
    val agent = createTestAgent()
    doThrow(RuntimeException("send failed"))
      .whenever(mockClient)
      .sendMessage(
        any<Message>(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )

    val result = runCatching { agent.runAsync(invocationContext).toList() }

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).hasMessageThat().contains("send failed")
    verify(mockClient).close()
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

  // Uses runBlocking (real time) because task cancellation runs on Dispatchers.IO.
  @Test
  fun runAsync_nonTerminalTaskAbandoned_cancelsRemoteTask() = runBlocking {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_WORKING, "working..."),
        agentCard,
      )
    }

    // Collecting a single event abandons the still-open task, triggering cleanup.
    agent.runAsync(invocationContext).first()

    verify(mockClient, timeout(5000)).cancelTask(any())
    Unit
  }

  @Test
  fun runAsync_nonTerminalTaskAbandoned_cancelsItInTheCallersContext() {
    // Stands in for MDC: thread-local state a caller carries in its coroutine context.
    val requestId = ThreadLocal<String?>()
    runBlocking(requestId.asContextElement("caller")) {
      val cancelledWith = CompletableDeferred<String?>()
      whenever(mockClient.cancelTask(any())).thenAnswer {
        cancelledWith.complete(requestId.get())
        null
      }
      val agent = createTestAgent()
      mockStreamResponse(this) { consumer ->
        consumer.accept(
          createStatusUpdateEvent(TaskState.TASK_STATE_WORKING, "working..."),
          agentCard,
        )
      }

      agent.runAsync(invocationContext).first()

      assertThat(withTimeout(5.seconds) { cancelledWith.await() }).isEqualTo("caller")
    }
  }

  @Test
  fun runAsync_terminalInputRequiredTaskAbandoned_doesNotCancelRemoteTask() = runBlocking {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "need input"),
        agentCard,
      )
    }

    // An input-required task is terminal, so abandoning the flow must not cancel it.
    agent.runAsync(invocationContext).first()

    verify(mockClient, after(500).never()).cancelTask(any())
    Unit
  }

  @Test
  fun runAsync_terminalAuthRequiredTaskAbandoned_doesNotCancelRemoteTask() = runBlocking {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_AUTH_REQUIRED, "need auth"),
        agentCard,
      )
    }

    // An auth-required task is terminal, so abandoning the flow must not cancel it.
    agent.runAsync(invocationContext).first()

    verify(mockClient, after(500).never()).cancelTask(any())
    Unit
  }

  @Test
  fun runAsync_inputRequired_completesFlow() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "need input"),
        agentCard,
      )
    }

    val events = agent.runAsync(invocationContext).toList()
    assertThat(events).isNotEmpty()
    assertThat(events.last().turnComplete).isTrue()
  }

  @Test
  fun runAsync_blockingSendAnsweredWithMessage_completesFlow() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      mockStreamResponse(this) { consumer ->
        consumer.accept(MessageEvent(agentMessage("Hi")), agentCard)
      }

      val events = withTimeout(5.seconds) { agent.runAsync(blockingContext()).toList() }

      assertThat(capturedStreaming).isFalse()
      assertThat(events.map { it.content?.parts?.single()?.text }).containsExactly("Hi")
    }

  // Uses runBlocking (real time) because task cancellation runs on Dispatchers.IO.
  @Test
  fun runAsync_blockingSendAnsweredWithWorkingTask_endsWithErrorAndCancelsTask() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      mockStreamResponse(this) { consumer ->
        consumer.accept(createTaskEvent(TaskState.TASK_STATE_WORKING, "working..."), agentCard)
      }

      val events = withTimeout(5.seconds) { agent.runAsync(blockingContext()).toList() }

      assertThat(events).hasSize(2)
      assertThat(events[0].content?.parts?.single()?.thought).isTrue()
      assertThat(events[1].errorMessage).isNotNull()
      // The next turn reads the remote context id from this agent's last event.
      assertThat(events[1].customMetadata)
        .containsAtLeast("a2a:context_id", "context-1", "adk_context_id", "context-1")
      assertThat(events[1].customMetadata).containsKey("a2a:request")
      verify(mockClient, timeout(5000)).cancelTask(any())
    }

  @Test
  fun runAsync_streamEndsWithWorkingTask_endsWithError() =
    runBlocking<Unit> {
      val agent = createTestAgent()
      mockStreamResponse(this, endsStream = true) { consumer ->
        consumer.accept(
          createStatusUpdateEvent(TaskState.TASK_STATE_WORKING, "working..."),
          agentCard,
        )
      }

      val events = withTimeout(5.seconds) { agent.runAsync(invocationContext).toList() }

      assertThat(events.last().errorMessage).isNotNull()
      assertThat(events.last().customMetadata)
        .containsAtLeast("a2a:context_id", "context-1", "adk_context_id", "context-1")
      assertThat(events.last().customMetadata).containsKey("a2a:request")
    }

  @Test
  fun runAsync_authRequired_completesFlow() = runTest {
    val agent = createTestAgent()
    mockStreamResponse(this) { consumer ->
      consumer.accept(
        createStatusUpdateEvent(TaskState.TASK_STATE_AUTH_REQUIRED, "need auth"),
        agentCard,
      )
    }

    val events = agent.runAsync(invocationContext).toList()
    assertThat(events).isNotEmpty()
    assertThat(events.last().turnComplete).isTrue()
  }

  @Test
  fun runAsync_blockingTaskCompletedWithFunctionResponse_yieldsFinalResponse() =
    runBlocking<Unit> {
      val response =
        DataPart(
          mapOf("name" to "lookup", "id" to "fr-1", "response" to mapOf("color" to "blue")),
          mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_RESPONSE),
        )
      val artifact = Artifact.builder().artifactId("artifact-1").parts(listOf(response)).build()
      val task =
        Task.builder()
          .id("task-1")
          .contextId("context-1")
          .status(TaskStatus(TaskState.TASK_STATE_COMPLETED))
          .artifacts(listOf(artifact))
          .build()
      mockStreamResponse(this) { consumer -> consumer.accept(TaskEvent(task), agentCard) }

      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      val events =
        InMemoryRunner(createTestAgent())
          .runAsync("user", "s1", newMessage = userMessage("Look it up"), runConfig = blocking)
          .toList()

      val answer = events.single { it.functionResponses().isNotEmpty() }
      assertThat(answer.isFinalResponse).isTrue()
    }

  @Test
  fun runAsync_answerToInputRequiredTask_resumesTaskWithAnswerText() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      val replies =
        ArrayDeque(
          listOf(
            createTaskEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
            createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Painted."),
          )
        )
      mockStreamResponse(this) { consumer -> consumer.accept(replies.removeFirst(), agentCard) }

      val firstTurn =
        runner.runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = blocking)
      val callEvent = firstTurn.toList().single { it.functionCalls().isNotEmpty() }
      val call = callEvent.functionCalls().single()
      assertThat(call.name).isEqualTo("mock_function_call_for_required_user_input")
      assertThat(call.args).containsExactly("input_required", "Which color?")
      assertThat(callEvent.longRunningToolIds).containsExactly(call.id)

      val answer = userFunctionResponse(call.name, call.id, mapOf("result" to "Blue"))
      runner.runAsync("user", "s1", newMessage = answer, runConfig = blocking).toList()

      val messages = argumentCaptor<Message>()
      verify(mockClient, times(2))
        .sendMessage(
          messages.capture(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )
      assertThat(messages.secondValue.taskId()).isEqualTo("task-1")
      assertThat(messages.secondValue.parts.map { (it as TextPart).text }).containsExactly("Blue")
    }

  @Test
  fun runAsync_firstTurnFailsAfterTaskOpened_nextTurnKeepsItsContext() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val streaming = RunConfig(streamingMode = StreamingMode.SSE)
      // Like a real server, the stream starts with the task's snapshot, which has no content.
      val submitted =
        Task.builder()
          .id("task-1")
          .contextId("context-1")
          .status(TaskStatus(TaskState.TASK_STATE_SUBMITTED))
          .build()
      val working = createStatusUpdateEvent(TaskState.TASK_STATE_WORKING)
      mockStreamError(
        this,
        RuntimeException("Connection reset"),
        responses = listOf(TaskEvent(submitted), working),
      )
      assertFailsWith<RuntimeException> {
        runner
          .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = streaming)
          .toList()
      }
      // Waits for the abandoned task's cancel to finish before the mock is stubbed again.
      verify(mockClient, timeout(5000)).close()
      mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("ok"), agentCard) }

      runner
        .runAsync("user", "s1", newMessage = userMessage("Again"), runConfig = streaming)
        .toList()

      // The snapshot recorded the task's context, which already holds the first turn's text.
      val messages = sentMessages(2)
      assertThat(messages.map { it.contextId() }).containsExactly(null, "context-1").inOrder()
      assertThat(messages[1].parts.map { (it as TextPart).text }).containsExactly("Again")
    }

  @Test
  fun runAsync_sendFailsBeforeAnyTask_yieldsErrorEventAndNextTurnKeepsContext() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      var sends = 0
      doAnswer { invocation ->
          if (++sends == 2) throw RuntimeException("Connection refused")
          val consumer = invocation.getArgument<List<BiConsumer<ClientEvent, AgentCard>>>(1)[0]
          consumer.accept(createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Done."), agentCard)
          null
        }
        .whenever(mockClient)
        .sendMessage(
          any<Message>(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )

      runner
        .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = blocking)
        .toList()
      val failedTurn = mutableListOf<Event>()
      assertFailsWith<RuntimeException> {
        runner
          .runAsync("user", "s1", newMessage = userMessage("Blue"), runConfig = blocking)
          .toList(failedTurn)
      }
      runner
        .runAsync("user", "s1", newMessage = userMessage("Green"), runConfig = blocking)
        .toList()

      assertThat(failedTurn.single().customMetadata).containsKey("a2a:request")
      // The failed request's event has no context, so the third turn keeps the first turn's.
      val messages = sentMessages(3)
      assertThat(messages.map { it.contextId() })
        .containsExactly(null, "context-1", "context-1")
        .inOrder()
      assertThat(messages[2].parts.map { (it as TextPart).text }).containsExactly("Blue", "Green")
    }

  @Test
  fun runAsync_streamEndsMidTask_nextTurnKeepsItsContext() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      mockStreamResponse(this, endsStream = true) { consumer ->
        consumer.accept(createStatusUpdateEvent(TaskState.TASK_STATE_WORKING), agentCard)
      }
      runner
        .runAsync(
          "user",
          "s1",
          newMessage = userMessage("Paint"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()
      // Waits for the abandoned task's cancel to finish before the mock is stubbed again.
      verify(mockClient, timeout(5000)).close()
      mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("ok"), agentCard) }

      runner
        .runAsync(
          "user",
          "s1",
          newMessage = userMessage("Again"),
          runConfig = RunConfig(streamingMode = StreamingMode.NONE),
        )
        .toList()

      // The remote opened a task, so it has the first turn's text, which is not sent again.
      val sent = sentMessages(2)[1]
      assertThat(sent.contextId()).isEqualTo("context-1")
      // The stream's end cancelled the first turn's task, so the next turn opens a new one.
      assertThat(sent.taskId()).isNull()
      assertThat(sent.parts.map { (it as TextPart).text }).containsExactly("Again")
    }

  @Test
  fun runAsync_replyAfterResponseWithoutContext_sendsNoContext() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      mockReplies(
        this,
        createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Painted."),
        MessageEvent(agentMessage("Hi")),
        MessageEvent(agentMessage("Hi again")),
      )

      for (text in listOf("Paint", "Hello", "Again")) {
        runner.runAsync("user", "s1", newMessage = userMessage(text), runConfig = blocking).toList()
      }

      // The second response carried no context id, so the third turn starts without one.
      val sent = sentMessages(3)
      assertThat(sent.map { it.contextId() }).containsExactly(null, "context-1", null).inOrder()
      // The second turn's response still counts, so only the third turn's text is sent.
      assertThat(sent[2].parts.map { (it as TextPart).text }).containsExactly("Again")
    }

  @Test
  fun runAsync_textReplyToInputRequiredTask_resumesTask() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      mockReplies(
        this,
        createTaskEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
        createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Painted."),
      )

      runner
        .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = blocking)
        .toList()
      runner.runAsync("user", "s1", newMessage = userMessage("Blue"), runConfig = blocking).toList()

      val messages = sentMessages(2)
      assertThat(messages[1].taskId()).isEqualTo("task-1")
      assertThat(messages[1].parts.map { (it as TextPart).text }).containsExactly("Blue")
    }

  @Test
  fun runAsync_textReplyAfterAuthAnswer_resumesAuthRequiredTask() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      mockReplies(
        this,
        createTaskEvent(TaskState.TASK_STATE_AUTH_REQUIRED, "Sign in first."),
        createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Done."),
      )

      val call =
        runner
          .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = blocking)
          .toList()
          .flatMap { it.functionCalls() }
          .single()
      val authAnswer = userFunctionResponse(call.name, call.id, mapOf("result" to "token"))
      runner.runAsync("user", "s1", newMessage = authAnswer, runConfig = blocking).toList()
      runner
        .runAsync("user", "s1", newMessage = userMessage("Signed in"), runConfig = blocking)
        .toList()

      // The auth answer is never sent, so the plain-text reply is what resumes the task.
      val messages = sentMessages(2)
      assertThat(messages[1].taskId()).isEqualTo("task-1")
      assertThat(messages[1].parts.map { (it as TextPart).text }).containsExactly("Signed in")
    }

  @Test
  fun runAsync_emptyAnswerToInputRequiredTask_resumesTaskWithTheAnswer() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      mockReplies(
        this,
        createTaskEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
        createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Painted."),
      )

      val call =
        runner
          .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = blocking)
          .toList()
          .flatMap { it.functionCalls() }
          .single()
      val emptyAnswer = userFunctionResponse(call.name, call.id, emptyMap())
      runner.runAsync("user", "s1", newMessage = emptyAnswer, runConfig = blocking).toList()

      // With no text to send, the history goes out on the paused task.
      val messages = sentMessages(2)
      assertThat(messages[1].taskId()).isEqualTo("task-1")
      val sent = messages[1].parts.single() as DataPart
      assertThat(sent.metadata?.get("adk_type")).isEqualTo("function_response")
    }

  @Test
  fun runAsync_textReplyAfterStreamEndedMidTask_startsNewTask() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val streaming = RunConfig(streamingMode = StreamingMode.SSE)
      mockReplies(
        this,
        createStatusUpdateEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
        createStatusUpdateEvent(TaskState.TASK_STATE_WORKING),
        createStatusUpdateEvent(TaskState.TASK_STATE_COMPLETED, "Done."),
        endsStream = true,
      )

      for (text in listOf("Paint", "Blue", "Green")) {
        runner
          .runAsync("user", "s1", newMessage = userMessage(text), runConfig = streaming)
          .toList()
      }

      // The second turn stored only the error event, and the third must not resume the paused task.
      val messages = sentMessages(3)
      assertThat(messages.map { it.taskId() }).containsExactly(null, "task-1", null).inOrder()
      // Unlike ADK Python, the ended stream's event keeps its context, so its turn is not resent.
      assertThat(messages[2].parts.map { (it as TextPart).text }).containsExactly("Green")
    }

  @Test
  fun runAsync_textRepliesToRepeatedPauses_resumeTheSameTask() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      mockReplies(
        this,
        createTaskEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
        createTaskEvent(TaskState.TASK_STATE_AUTH_REQUIRED, "Sign in to paint."),
        createTaskEvent(TaskState.TASK_STATE_COMPLETED, "Painted."),
      )

      for (text in listOf("Paint", "Blue", "Signed in")) {
        runner.runAsync("user", "s1", newMessage = userMessage(text), runConfig = blocking).toList()
      }

      assertThat(sentMessages(3).map { it.taskId() })
        .containsExactly(null, "task-1", "task-1")
        .inOrder()
    }

  @Test
  fun runAsync_textReplyToPauseWithoutMessage_resumesTask() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val streaming = RunConfig(streamingMode = StreamingMode.SSE)
      mockReplies(
        this,
        createStatusUpdateEvent(TaskState.TASK_STATE_INPUT_REQUIRED),
        createStatusUpdateEvent(TaskState.TASK_STATE_COMPLETED, "Done."),
        endsStream = true,
      )

      for (text in listOf("Paint", "Blue")) {
        runner
          .runAsync("user", "s1", newMessage = userMessage(text), runConfig = streaming)
          .toList()
      }

      assertThat(sentMessages(2).map { it.taskId() }).containsExactly(null, "task-1").inOrder()
    }

  @Test
  fun runAsync_textReplyAfterFailedResume_startsNewTaskInSameContext() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val blocking = RunConfig(streamingMode = StreamingMode.NONE)
      var sends = 0
      doAnswer { invocation ->
          if (++sends == 2) throw RuntimeException("Task is in a terminal state")
          val consumer = invocation.getArgument<List<BiConsumer<ClientEvent, AgentCard>>>(1)[0]
          consumer.accept(
            createTaskEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
            agentCard,
          )
          null
        }
        .whenever(mockClient)
        .sendMessage(
          any<Message>(),
          any<List<BiConsumer<ClientEvent, AgentCard>>>(),
          any<Consumer<Throwable>>(),
          isNull(),
        )

      runner
        .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = blocking)
        .toList()
      assertFailsWith<RuntimeException> {
        runner
          .runAsync("user", "s1", newMessage = userMessage("Blue"), runConfig = blocking)
          .toList()
      }
      runner
        .runAsync("user", "s1", newMessage = userMessage("Green"), runConfig = blocking)
        .toList()

      // The failed second turn may have resumed the paused task, so the third doesn't retry it.
      val messages = sentMessages(3)
      assertThat(messages.map { it.taskId() }).containsExactly(null, "task-1", null).inOrder()
      assertThat(messages[2].contextId()).isEqualTo("context-1")
    }

  @Test
  fun runAsync_textReplyAfterStreamedChunksAndInputRequired_resumesTask() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      val streaming = RunConfig(streamingMode = StreamingMode.SSE)
      val replies =
        ArrayDeque(
          listOf(
            listOf(
              createPartialEvent("Hello ", true, false),
              createPartialEvent("World!", true, false),
              createStatusUpdateEvent(TaskState.TASK_STATE_INPUT_REQUIRED, "Which color?"),
            ),
            listOf(createStatusUpdateEvent(TaskState.TASK_STATE_COMPLETED, "Painted.")),
          )
        )
      mockStreamResponse(this) { consumer ->
        replies.removeFirst().forEach { consumer.accept(it, agentCard) }
      }

      runner
        .runAsync("user", "s1", newMessage = userMessage("Paint"), runConfig = streaming)
        .toList()
      runner
        .runAsync("user", "s1", newMessage = userMessage("Blue"), runConfig = streaming)
        .toList()

      assertThat(sentMessages(2)[1].taskId()).isEqualTo("task-1")
    }

  @Test
  fun runAsync_turnStoppedAtMergedEvent_nextTurnKeepsRemoteContext() =
    runBlocking<Unit> {
      val runner = InMemoryRunner(createTestAgent())
      mockStreamResponse(this) { consumer ->
        consumer.accept(createPartialEvent("Hello ", true, false), agentCard)
        consumer.accept(createPartialEvent("World!", true, true), agentCard)
        consumer.accept(createStatusUpdateEvent(TaskState.TASK_STATE_COMPLETED, "Done."), agentCard)
      }
      // An app that stops at the merged answer leaves it as this agent's latest stored event.
      runner
        .runAsync(
          "user",
          "s1",
          newMessage = userMessage("Hi"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .first { it.content?.parts?.singleOrNull()?.text == "Hello World!" }
      // Waits for the abandoned task's cancel to finish before the mock is stubbed again.
      verify(mockClient, timeout(5000)).close()
      mockStreamResponse(this) { consumer -> consumer.accept(createFinalEvent("ok"), agentCard) }

      runner
        .runAsync(
          "user",
          "s1",
          newMessage = userMessage("Again"),
          runConfig = RunConfig(streamingMode = StreamingMode.NONE),
        )
        .toList()

      assertThat(sentMessages(2)[1].contextId()).isEqualTo("context-1")
    }

  private fun createTestAgent(
    transportSupportsStreaming: Boolean = true,
    beforeCallbacks: List<BeforeAgentCallback> = emptyList(),
    afterCallbacks: List<AfterAgentCallback> = emptyList(),
  ): BaseRemoteA2AAgent {
    return A2AAgentImpl(
      name = "remote-agent",
      clientProvider = { streaming ->
        capturedStreaming = streaming
        mockClient
      },
      agentCard = agentCard,
      transportSupportsStreaming = transportSupportsStreaming,
      beforeAgentCallbacks = beforeCallbacks,
      afterAgentCallbacks = afterCallbacks,
    )
  }

  private fun assertEventText(event: Event?, expectedText: String) {
    assertThat(event?.content?.parts?.firstOrNull()?.text).isEqualTo(expectedText)
  }

  /** Streams [responses], then fails with [error]. */
  private fun mockStreamError(
    scope: CoroutineScope,
    error: Throwable,
    responses: List<ClientEvent> = emptyList(),
  ) {
    doAnswer { invocation ->
        val consumer = invocation.getArgument<List<BiConsumer<ClientEvent, AgentCard>>>(1)[0]
        val errorConsumer = invocation.getArgument<Consumer<Throwable>>(2)
        scope.launch {
          delay(10)
          responses.forEach { consumer.accept(it, agentCard) }
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

  private fun blockingContext(): InvocationContext =
    invocationContext.copy(runConfig = RunConfig(streamingMode = StreamingMode.NONE))

  // Like the SDK: delivers before sendMessage returns when blocking, and later when streaming.
  private fun mockStreamResponse(
    scope: CoroutineScope,
    endsStream: Boolean = false,
    responseProducer: (BiConsumer<ClientEvent, AgentCard>) -> Unit,
  ) {
    doAnswer { invocation ->
        val consumers = invocation.getArgument<List<BiConsumer<ClientEvent, AgentCard>>>(1)
        val consumer = consumers[0]
        if (capturedStreaming == false) {
          responseProducer(consumer)
        } else {
          scope.launch {
            delay(10)
            responseProducer(consumer)
            if (endsStream) invocation.getArgument<Consumer<Throwable?>>(2).accept(null)
          }
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

  /** Returns the messages sent to the remote agent, checking that there were [count]. */
  private fun sentMessages(count: Int): List<Message> {
    val messages = argumentCaptor<Message>()
    verify(mockClient, times(count))
      .sendMessage(
        messages.capture(),
        any<List<BiConsumer<ClientEvent, AgentCard>>>(),
        any<Consumer<Throwable>>(),
        isNull(),
      )
    return messages.allValues
  }

  /** Answers each request with the next of [replies]. */
  private fun mockReplies(
    scope: CoroutineScope,
    vararg replies: ClientEvent,
    endsStream: Boolean = false,
  ) {
    val queue = ArrayDeque(replies.toList())
    mockStreamResponse(scope, endsStream) { consumer ->
      consumer.accept(queue.removeFirst(), agentCard)
    }
  }

  private fun agentMessage(text: String): Message =
    Message.builder().role(Message.Role.ROLE_AGENT).parts(listOf(TextPart(text))).build()

  private fun createTaskEvent(state: TaskState, text: String): TaskEvent {
    val task =
      Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(state, agentMessage(text), null))
        .build()
    return TaskEvent(task)
  }

  /** A status update for task-1 in [state]; without [text] it carries no message or event. */
  private fun createStatusUpdateEvent(state: TaskState, text: String? = null): ClientEvent {
    val task = Task.builder().id("task-1").contextId("context-1").status(TaskStatus(state)).build()
    val update =
      TaskStatusUpdateEvent.builder()
        .taskId("task-1")
        .contextId("context-1")
        .status(TaskStatus(state, text?.let { agentMessage(it) }, null))
        .build()
    return TaskUpdateEvent(task, update)
  }

  private fun createPartialEvent(text: String, append: Boolean, lastChunk: Boolean): ClientEvent {
    return createTestEvent(TextPart(text), TaskState.TASK_STATE_WORKING, append, lastChunk)
  }

  private fun createPartialFunctionCallEvent(name: String, id: String): ClientEvent {
    val data = mapOf("name" to name, "id" to id, "args" to mapOf<String, Any>())
    val metadata = mapOf("adk_type" to "function_call")
    return createTestEvent(DataPart(data, metadata), TaskState.TASK_STATE_WORKING, true, false)
  }

  private fun createPartialFileEvent(uri: String, mimeType: String): ClientEvent {
    return createTestEvent(
      FilePart(FileWithUri(mimeType, "file", uri)),
      TaskState.TASK_STATE_WORKING,
      true,
      false,
    )
  }

  private fun createFinalEvent(text: String): ClientEvent {
    return createTestEvent(TextPart(text), TaskState.TASK_STATE_COMPLETED, false, false)
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
    val artifact = Artifact.builder().artifactId("artifact-1").parts(parts).build()
    val task =
      Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(state))
        .artifacts(listOf(artifact))
        .build()

    if (state == TaskState.TASK_STATE_COMPLETED && !append && !lastChunk) {
      return TaskEvent(task)
    }

    val updateEvent =
      TaskArtifactUpdateEvent.builder()
        .lastChunk(lastChunk)
        .append(append)
        .contextId("context-1")
        .artifact(artifact)
        .taskId("task-id-1")
        .build()
    return TaskUpdateEvent(task, updateEvent)
  }
}
