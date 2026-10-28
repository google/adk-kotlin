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
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking

class BaseFutureToolTest {

  private fun answers(callId: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
    message is ContentInput &&
      message.content.parts.any {
        it.functionResponse?.id == callId && it.functionResponse.response == response
      }
  }

  /** A Java-style tool whose future completes with a publisher of [items]. */
  private fun publishingTool(vararg items: String) =
    object : BaseFutureTool("prices", "Streams prices.") {
      override fun declaration(): FunctionDeclaration? = null

      override fun runAsync(context: ToolContext, args: Map<String, Any?>): CompletableFuture<Any> =
        CompletableFuture.completedFuture(flowOf(*items).asPublisher())
    }

  @Test
  fun run_futureCompletesWithAPublisher_streamsUnderALiveRun() {
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse("prices", id = "c1")),
          AwaitSent("the pending answer", answers("c1", PENDING)),
          AwaitSent("the first price", answers("c1", mapOf("result" to "300"))),
          AwaitSent("the second price", answers("c1", mapOf("result" to "400"))),
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
  fun run_publisherOutsideALiveRun_answersWithItsOneItem() {
    runBlocking {
      val model =
        DummyModel.createSequential(
          "model",
          listOf(
            modelFunctionCallResponse("prices", id = "c1"),
            LlmResponse(content = modelMessage("done")),
          ),
        )
      val agent = LlmAgent(name = "agent", model = model, tools = listOf(publishingTool("300")))

      val events =
        InMemoryRunner(agent = agent)
          .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("run"))
          .toList()

      val answer = events.flatMap { it.functionResponses() }.single { it.id == "c1" }
      assertThat(answer.response).isEqualTo(mapOf("result" to "300"))
    }
  }

  companion object {
    private val PENDING =
      mapOf("status" to "The function is running asynchronously and the results are pending.")
  }
}
