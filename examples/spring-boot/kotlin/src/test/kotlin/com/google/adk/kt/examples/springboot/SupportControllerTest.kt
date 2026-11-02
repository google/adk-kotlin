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

package com.google.adk.kt.examples.springboot

import com.google.adk.kt.examples.springboot.SupportService.AgentMessage
import com.google.adk.kt.examples.springboot.SupportService.AgentMessage.Type
import com.google.adk.kt.examples.springboot.SupportService.SupportReply
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult

/** Offline tests of both support endpoints, with a scripted model in place of Gemini. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SupportControllerTest {

  @TestBean lateinit var model: Model

  @Autowired lateinit var client: WebTestClient

  @Autowired lateinit var runner: Runner

  @Autowired lateinit var sessionService: SessionService

  @Autowired lateinit var orderService: OrderService

  @Test
  fun answer_sendsADeliveryQuestionToTheShippingAgent_whichTracksTheOrder() {
    assertThat(answer(WHERE_IS_1042)).isEqualTo(SupportReply(TRACKING_REPLY))
  }

  @Test
  fun answer_sendsAnApprovedRefundToTheRefundsAgent_whichCreatesALabel() {
    assertThat(answer("Please refund order 1043."))
      .isEqualTo(SupportReply("Refund approved. Return label RL-1043."))
  }

  @Test
  fun answer_deniesARefundInCode() {
    // The refund_denied node writes these replies; the refunds agent never runs.
    assertThat(answer("Please refund order 1044.")).isEqualTo(SupportReply(REFUND_DENIED_1044))
    assertThat(answer("Please refund order 1042."))
      .isEqualTo(
        SupportReply(
          "Your refund for order 1042 (noise-cancelling headphones) was denied, because the " +
            "order has not been delivered yet."
        )
      )
  }

  @Test
  fun createReturnLabel_refusesAnOrderThePolicyDenies() {
    assertThat(orderService.createReturnLabel("1043")).containsEntry("labelNumber", "RL-1043")
    assertThat(orderService.createReturnLabel("1044")).containsKey("error")
    assertThat(orderService.createReturnLabel("1042")).containsKey("error")
    assertThat(orderService.createReturnLabel("9999")).containsKey("error")
  }

  @Test
  fun answer_withoutAnOrderToActOn_asksForTheOrderNumber() {
    for (message in
      listOf(
        "Hi, can you help me?",
        "Where is my order 9999?",
        "Can I change the address on order 1042?",
      )) {
      assertThat(answer(message).text).contains("order number")
    }
  }

  @Test
  fun streamAnswer_streamsTheProgressThatNodesPublish_thenTheReply() {
    assertThat(streamMessages(WHERE_IS_1042))
      .containsExactly(
        AgentMessage(Type.PROGRESS, "Found order 1042 (noise-cancelling headphones)."),
        AgentMessage(Type.PROGRESS, "Checking the carrier's tracking..."),
        AgentMessage(Type.REPLY, TRACKING_REPLY),
      )
      .inOrder()
  }

  @Test
  fun streamAnswer_forAnApprovedRefund_reportsEachStep() {
    assertThat(streamMessages("Please refund order 1043."))
      .containsExactly(
        AgentMessage(Type.PROGRESS, "Found order 1043 (espresso machine)."),
        AgentMessage(Type.PROGRESS, "Checking the refund policy..."),
        AgentMessage(Type.PROGRESS, "Creating a return label..."),
        AgentMessage(Type.REPLY, "Refund approved. Return label RL-1043."),
      )
      .inOrder()
  }

  @Test
  fun streamAnswer_forADeniedRefund_reportsThePolicyCheck_thenTheReply() {
    assertThat(streamMessages("Please refund order 1044."))
      .containsExactly(
        AgentMessage(Type.PROGRESS, "Found order 1044 (running shoes)."),
        AgentMessage(Type.PROGRESS, "Checking the refund policy..."),
        AgentMessage(Type.REPLY, REFUND_DENIED_1044),
      )
      .inOrder()
  }

  @Test
  fun streamAnswer_withoutAnOrderToActOn_sendsOnlyTheReply() {
    for (message in
      listOf(
        "Hi, can you help me?",
        "Where is my order 9999?",
        "Can I change the address on order 1042?",
      )) {
      val messages = streamMessages(message)

      // Without a matched route no node publishes progress, and triage's answer stays internal.
      assertThat(messages.map { it.type }).containsExactly(Type.REPLY)
      assertThat(messages.single().text).contains("order number")
    }
  }

  @Test
  fun answer_deletesItsSessionWhenTheRunEnds() {
    answer(WHERE_IS_1042)

    assertThat(storedSessions()).isEmpty()
  }

  @Test
  fun answer_whenTheReplyIsBlocked_answers502AndDeletesItsSession() {
    post("/support", "$WHERE_IS_1042 [blocked]").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)

    assertThat(storedSessions()).isEmpty()
  }

  @Test
  fun answer_whenTheReplyIsEmpty_answers502() {
    post("/support", "$WHERE_IS_1042 [silent]").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
  }

  @Test
  fun answer_whenTheModelCallThrows_answers502() {
    post("/support", "$WHERE_IS_1042 [failing]").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
  }

  @Test
  fun streamAnswer_whenTheReplyIsBlocked_endsWithAnErrorEvent() {
    val events = streamEvents("$WHERE_IS_1042 [blocked]")

    val last = events.last()
    assertThat(last.event()).isEqualTo("error")
    assertThat(last.data()).contains(SupportWorkflowConfiguration.SHIPPING)
    assertThat(storedSessions()).isEmpty()
  }

  @Test
  fun streamAnswer_whenTheReplyIsEmpty_endsWithAnErrorEvent() {
    val events = streamEvents("$WHERE_IS_1042 [silent]")

    // The progress messages arrive, but no reply follows them.
    assertThat(events.first().data()).contains("Found order 1042")
    val last = events.last()
    assertThat(last.event()).isEqualTo("error")
    assertThat(last.data()).contains("without a reply")
  }

  @Test
  fun streamAnswer_whenTheModelCallThrows_endsWithAnErrorEvent() {
    val events = streamEvents("$WHERE_IS_1042 [failing]")

    val last = events.last()
    assertThat(last.event()).isEqualTo("error")
    assertThat(last.data()).contains(SupportWorkflowConfiguration.SHIPPING)
    // Only the error code reaches the client, not the exception's message.
    assertThat(last.data()).doesNotContain(ScriptedModel.QUOTA_ERROR)
  }

  @Test
  fun streamAnswer_whenTheClientDisconnects_deletesItsSession() {
    // Disconnects after the progress messages, while the shipping agent waits for the model.
    runBlocking { withTimeout(TIMEOUT) { stream("$WHERE_IS_1042 [endless]").take(2).toList() } }

    assertThat(storedSessionsAfterCleanup()).isEmpty()
  }

  @Test
  fun bothEndpoints_rejectABlankMessage() {
    post("/support", " ").expectStatus().isBadRequest()
    post("/support/stream", " ").expectStatus().isBadRequest()
  }

  private fun answer(message: String): SupportReply =
    checkNotNull(
      post("/support", message)
        .expectStatus()
        .isOk()
        .expectBody<SupportReply>()
        .returnResult()
        .responseBody
    ) {
      "The response has no body."
    }

  private fun post(path: String, message: String): WebTestClient.ResponseSpec =
    client.post().uri(path).bodyValue(mapOf("message" to message)).exchange()

  private fun stream(message: String): Flow<AgentMessage> =
    postStream(message).returnResult<AgentMessage>().responseBody.asFlow()

  private fun streamMessages(message: String): List<AgentMessage> = runBlocking {
    withTimeout(TIMEOUT) { stream(message).toList() }
  }

  private fun streamEvents(message: String): List<ServerSentEvent<String>> = runBlocking {
    withTimeout(TIMEOUT) {
      postStream(message).returnResult<ServerSentEvent<String>>().responseBody.asFlow().toList()
    }
  }

  private fun postStream(message: String): WebTestClient.ResponseSpec =
    client
      .post()
      .uri("/support/stream")
      .accept(MediaType.TEXT_EVENT_STREAM)
      .bodyValue(mapOf("message" to message))
      .exchange()
      .expectStatus()
      .isOk()

  private fun storedSessions(): List<Session> = runBlocking { listStoredSessions() }

  /** Returns the stored sessions, waiting up to five seconds for the server to delete them. */
  private fun storedSessionsAfterCleanup(): List<Session> = runBlocking {
    withTimeoutOrNull(5.seconds) { while (listStoredSessions().isNotEmpty()) delay(50) }
    listStoredSessions()
  }

  private suspend fun listStoredSessions(): List<Session> =
    sessionService.listSessions(runner.appName, SupportService.USER_ID).sessions

  companion object {
    private const val WHERE_IS_1042 = "Where is my order 1042?"
    private const val TRACKING_REPLY = "Tracking says: Out for delivery"
    private const val REFUND_DENIED_1044 =
      "Your refund for order 1044 (running shoes) was denied, because the order was delivered " +
        "more than 30 days ago."
    private val TIMEOUT = 30.seconds

    /**
     * Supplies the scripted model in place of Gemini; [TestBean] finds this method by the field's
     * name.
     */
    @JvmStatic fun model(): Model = ScriptedModel()
  }
}

/**
 * A test model that picks its behavior from each agent's instruction: triage classifies the
 * message, and the shipping and refunds agents call their tools, then reply from the results. A
 * message marked `[blocked]` makes the reply fail with an error code, `[failing]` makes the model
 * call throw, `[silent]` makes the reply come back empty after the tool call, and `[endless]` makes
 * it never finish.
 */
internal class ScriptedModel : Model {
  override val name = "scripted-model"

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    val instruction = request.config.systemInstruction?.text().orEmpty()
    val message = request.contents.filter { it.role == Role.USER }.joinToString("\n") { it.text() }
    if ("Classify the customer's message" in instruction) return reply(triage(message))
    if ("[blocked]" in message) {
      return flowOf(LlmResponse(finishReason = FinishReason.SAFETY, errorCode = "SAFETY"))
    }
    if ("[failing]" in message) return flow { throw IllegalStateException(QUOTA_ERROR) }
    if ("[endless]" in message) return flow { awaitCancellation() }
    val toolResult = lastToolResult(request)
    if ("[silent]" in message && toolResult != null) return reply("")
    if ("delivery questions" in instruction) {
      return if (toolResult == null) {
        callTool("trackShipment", message)
      } else {
        reply("Tracking says: ${toolResult["status"]}")
      }
    }
    return if (toolResult == null) {
      callTool("createReturnLabel", message)
    } else {
      reply("Refund approved. Return label ${toolResult["labelNumber"]}.")
    }
  }

  /** Returns the triage agent's structured answer for [message]. */
  private fun triage(message: String): String {
    val intent =
      when {
        "refund" in message -> "refund"
        "Where" in message -> "tracking"
        else -> "other"
      }
    return """{"intent": "$intent", "orderId": "${ORDER_ID.find(message)?.value.orEmpty()}"}"""
  }

  /**
   * Calls [tool] for the order in the agent's input, after writing a line of text as models often
   * do.
   */
  private fun callTool(tool: String, message: String): Flow<LlmResponse> {
    val orderId = ORDER_ID.find(message)?.value.orEmpty()
    val call = FunctionCall(name = tool, args = mapOf("orderId" to orderId))
    val parts = listOf(Part(text = "Let me check."), Part(functionCall = call))
    return flowOf(LlmResponse(content = Content(role = Role.MODEL, parts = parts)))
  }

  /** Returns the tool's result if [request] ends with a tool response, or null. */
  private fun lastToolResult(request: LlmRequest): Map<*, *>? =
    request.contents.last().parts.firstNotNullOfOrNull {
      it.functionResponse?.response?.get("result") as? Map<*, *>
    }

  private fun reply(text: String): Flow<LlmResponse> =
    flowOf(LlmResponse(content = Content.fromText(Role.MODEL, text)))

  companion object {
    const val QUOTA_ERROR = "quota exceeded"
    private val ORDER_ID = Regex("\\d{4}")
  }
}
