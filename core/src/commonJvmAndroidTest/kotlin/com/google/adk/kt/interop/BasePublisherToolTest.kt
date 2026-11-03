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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalLiveApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher

class BasePublisherToolTest {

  private fun answers(callId: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
    message is ContentInput &&
      message.content.parts.any {
        it.functionResponse?.id == callId && it.functionResponse.response == response
      }
  }

  /** A Java-style tool that publishes [items]. */
  private fun publishingTool(vararg items: String) =
    object : BasePublisherTool(TOOL_NAME, "Streams prices.") {
      override fun declaration(): FunctionDeclaration? = null

      override fun runPublisher(context: ToolContext, args: Map<String, Any?>): Publisher<String> =
        flowOf(*items).asPublisher()
    }

  @Test
  fun run_underALiveRun_streamsEachValue() {
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse(TOOL_NAME, id = CALL_ID)),
          AwaitSent("the pending answer", answers(CALL_ID, PENDING)),
          AwaitSent("the first price", answers(CALL_ID, mapOf("result" to "300"))),
          AwaitSent("the second price", answers(CALL_ID, mapOf("result" to "400"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val model = DummyLiveModel(script)
      val agent =
        LlmAgent(name = "agent", model = model, tools = listOf(publishingTool("300", "400")))
      val queue = LiveRequestQueue()

      val unused =
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig(streamingMode = StreamingMode.BIDI))
          .onEach { if (it.turnComplete) queue.close() }
          .toList()

      model.connections.single().assertAllWaitsMet()
    }
  }

  @Test
  fun run_outsideALiveRun_fails() {
    runBlocking {
      val model =
        DummyModel.createSequential(
          "model",
          listOf(modelFunctionCallResponse(TOOL_NAME, id = CALL_ID)),
        )
      val agent = LlmAgent(name = "agent", model = model, tools = listOf(publishingTool("300")))

      val error =
        assertFailsWith<IllegalStateException> {
          InMemoryRunner(agent = agent)
            .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("run"))
            .toList()
        }

      assertThat(error)
        .hasMessageThat()
        .isEqualTo("Only a connected live run can stream a tool's Flow; return a value instead.")
    }
  }

  companion object {
    private val PENDING =
      mapOf("status" to "The function is running asynchronously and the results are pending.")

    /** The name of the publishing tool the scripted model calls. */
    private const val TOOL_NAME = "prices"

    /** The id the scripted model gives that call. */
    private const val CALL_ID = "c1"
  }
}
