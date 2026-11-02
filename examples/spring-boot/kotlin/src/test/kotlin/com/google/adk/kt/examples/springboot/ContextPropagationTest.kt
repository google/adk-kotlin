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
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult
import reactor.core.publisher.Hooks

/**
 * Tests both support endpoints with Micrometer context propagation enabled, as in apps that trace
 * requests, using a model that suspends like a network call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ContextPropagationTest {

  @TestBean lateinit var model: Model

  @Autowired lateinit var client: WebTestClient

  @Test
  fun answer_whenTheModelSuspends_returnsTheReply() {
    val reply =
      client
        .post()
        .uri("/support")
        .bodyValue(mapOf("message" to WHERE_IS_1042))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody<SupportReply>()
        .returnResult()
        .responseBody

    assertThat(reply).isEqualTo(SupportReply(TRACKING_REPLY))
  }

  @Test
  fun streamAnswer_whenTheModelSuspends_streamsTheProgressThenTheReply() {
    val messages = runBlocking {
      withTimeout(30.seconds) {
        client
          .post()
          .uri("/support/stream")
          .accept(MediaType.TEXT_EVENT_STREAM)
          .bodyValue(mapOf("message" to WHERE_IS_1042))
          .exchange()
          .expectStatus()
          .isOk()
          .returnResult<AgentMessage>()
          .responseBody
          .asFlow()
          .toList()
      }
    }

    assertThat(messages)
      .containsExactly(
        AgentMessage(Type.PROGRESS, "Found order 1042 (noise-cancelling headphones)."),
        AgentMessage(Type.PROGRESS, "Checking the carrier's tracking..."),
        AgentMessage(Type.REPLY, TRACKING_REPLY),
      )
      .inOrder()
  }

  companion object {
    private const val WHERE_IS_1042 = "Where is my order 1042?"
    private const val TRACKING_REPLY = "Tracking says: Out for delivery"

    /** Supplies the model; [TestBean] finds this method by the field's name. */
    @JvmStatic fun model(): Model = SuspendingModel(ScriptedModel())

    // What `spring.reactor.context-propagation=auto` turns on, scoped to this class.
    @JvmStatic
    @BeforeAll
    fun enableContextPropagation() {
      Hooks.enableAutomaticContextPropagation()
    }

    @JvmStatic
    @AfterAll
    fun disableContextPropagation() {
      Hooks.disableAutomaticContextPropagation()
    }
  }
}

/**
 * Suspends before each model call so the run resumes on another thread, as with a real model. On
 * kotlinx-coroutines 1.11.0, resuming there restored Spring's thread-context element out of order
 * (https://github.com/Kotlin/kotlinx.coroutines/issues/4754) and caused the request to fail.
 */
private class SuspendingModel(private val delegate: Model) : Model by delegate {
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    delay(10.milliseconds)
    emitAll(delegate.generateContent(request, stream))
  }
}
