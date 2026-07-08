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

package com.google.adk.kt.a2a.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.a2aproject.sdk.client.http.A2AHttpClient
import org.a2aproject.sdk.client.http.AndroidA2AHttpClient
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.spec.A2AClientException
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.MessageSendParams
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TaskStatus
import org.a2aproject.sdk.spec.TextPart
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Directly exercises [JsonRpcHttpClientTransport] over a [MockWebServer] on the Robolectric
 * runtime.
 */
@RunWith(AndroidJUnit4::class)
class JsonRpcHttpClientTransportTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun transport(): JsonRpcHttpClientTransport =
    JsonRpcHttpClientTransport(AndroidA2AHttpClient(), server.url("/a2a").toString())

  private fun sendMessageParams(): MessageSendParams {
    val message =
      Message.builder()
        .messageId("req-1")
        .role(Message.Role.ROLE_USER)
        .parts(listOf(TextPart("hello")))
        .build()
    return MessageSendParams.builder().message(message).build()
  }

  @Test
  fun sendMessage_taskResponse_returnsTask() {
    val task =
      Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.TASK_STATE_COMPLETED))
        .build()
    server.enqueue(
      MockResponse().setBody(JsonUtil.toJson(SendMessageResponse("2.0", "1", task, null)))
    )

    val result = transport().sendMessage(sendMessageParams(), null)

    assertThat(result).isInstanceOf(Task::class.java)
    assertThat((result as Task).id).isEqualTo("task-1")
  }

  @Test
  fun sendMessage_sendsVersionHeaderAndFlatMessage() {
    val task =
      Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.TASK_STATE_COMPLETED))
        .build()
    server.enqueue(
      MockResponse().setBody(JsonUtil.toJson(SendMessageResponse("2.0", "1", task, null)))
    )

    assertThat(transport().sendMessage(sendMessageParams(), null)).isNotNull()

    val recorded = server.takeRequest()
    assertThat(recorded.getHeader("A2A-Version")).isEqualTo("1.0")
    // params.message is the flat message, not double-wrapped by the wrapper adapter.
    assertThat(recorded.body.readUtf8()).doesNotContain("\"message\":{\"message\"")
  }

  @Test
  fun sendMessage_messageResponse_returnsMessage() {
    val message =
      Message.builder()
        .messageId("msg-1")
        .role(Message.Role.ROLE_AGENT)
        .parts(listOf(TextPart("hi")))
        .build()
    server.enqueue(
      MockResponse().setBody(JsonUtil.toJson(SendMessageResponse("2.0", "1", message, null)))
    )

    val result = transport().sendMessage(sendMessageParams(), null)

    assertThat(result).isInstanceOf(Message::class.java)
    assertThat((result as Message).messageId).isEqualTo("msg-1")
  }

  @Test
  fun sendMessage_non2xxStatus_throws() {
    server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

    assertThrows(A2AClientException::class.java) {
      transport().sendMessage(sendMessageParams(), null)
    }
  }

  @Test
  fun sendMessage_jsonRpcError_throws() {
    server.enqueue(
      MockResponse()
        .setBody(
          "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32000,\"message\":\"boom\"}}"
        )
    )

    val e =
      assertThrows(A2AClientException::class.java) {
        transport().sendMessage(sendMessageParams(), null)
      }
    assertThat(e).hasMessageThat().contains("A2A JSON-RPC error")
  }

  @Test
  fun sendMessage_missingResult_throws() {
    server.enqueue(MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\"}"))

    val e =
      assertThrows(A2AClientException::class.java) {
        transport().sendMessage(sendMessageParams(), null)
      }
    assertThat(e).hasMessageThat().contains("missing 'result'")
  }

  @Test
  fun sendMessage_nonObjectResult_throws() {
    server.enqueue(MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"oops\"}"))

    val e =
      assertThrows(A2AClientException::class.java) {
        transport().sendMessage(sendMessageParams(), null)
      }
    assertThat(e).hasMessageThat().contains("missing 'result'")
  }

  @Test
  fun sendMessage_malformedJson_throws() {
    server.enqueue(MockResponse().setBody("not json"))

    assertThrows(A2AClientException::class.java) {
      transport().sendMessage(sendMessageParams(), null)
    }
  }

  private fun agentInterface(): AgentInterface =
    AgentInterface("JSONRPC", server.url("/a2a").toString())

  private fun agentCard(): AgentCard =
    AgentCard.builder()
      .name("remote-agent")
      .description("Remote Agent")
      .url(server.url("/a2a").toString())
      .version("1.0.0")
      .defaultInputModes(listOf("text"))
      .defaultOutputModes(listOf("text"))
      .skills(listOf())
      .supportedInterfaces(listOf(agentInterface()))
      .capabilities(AgentCapabilities.builder().streaming(false).build())
      .build()

  @Test
  fun sendMessage_usesInjectedHttpClient() {
    val marker = RuntimeException("injected client used")
    val injected =
      object : A2AHttpClient {
        override fun createGet(): A2AHttpClient.GetBuilder = throw marker

        override fun createPost(): A2AHttpClient.PostBuilder = throw marker

        override fun createDelete(): A2AHttpClient.DeleteBuilder = throw marker
      }

    val transport = JsonRpcHttpClientTransport(injected, server.url("/a2a").toString())
    val thrown =
      assertThrows(A2AClientException::class.java) {
        transport.sendMessage(sendMessageParams(), null)
      }

    assertThat(thrown).hasCauseThat().isSameInstanceAs(marker)
  }

  @Test
  fun sendMessage_interrupted_restoresFlagAndWraps() {
    val interrupting =
      object : A2AHttpClient {
        override fun createGet(): A2AHttpClient.GetBuilder = throw UnsupportedOperationException()

        override fun createPost(): A2AHttpClient.PostBuilder = throw InterruptedException("boom")

        override fun createDelete(): A2AHttpClient.DeleteBuilder =
          throw UnsupportedOperationException()
      }
    val transport = JsonRpcHttpClientTransport(interrupting, server.url("/a2a").toString())

    val e =
      assertThrows(A2AClientException::class.java) {
        transport.sendMessage(sendMessageParams(), null)
      }

    assertThat(e).hasMessageThat().contains("interrupted")
    assertThat(Thread.interrupted()).isTrue()
  }
}
