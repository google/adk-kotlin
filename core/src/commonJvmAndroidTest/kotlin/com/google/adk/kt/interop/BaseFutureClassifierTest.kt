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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalClassifierApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.classifiers.Binary
import com.google.adk.kt.classifiers.BinaryAnswer
import com.google.adk.kt.classifiers.ClassifierRequest
import com.google.adk.kt.classifiers.ClassifierResponse
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BaseFutureClassifierTest {

  private val sampleRequest =
    ClassifierRequest("payload", mapOf("risky" to Binary(instructions = "Risky?")))
  private val cannedResponse =
    ClassifierResponse(
      answers = mapOf("risky" to BinaryAnswer(probability = 0.25)),
      modelVersion = "jev-1.13.0",
    )

  @Test
  fun name_isTheConstructorArgument() {
    assertThat(futureClassifier("future-model") { CompletableFuture() }.name)
      .isEqualTo("future-model")
  }

  @Test
  fun classify_forwardsTheRequestAndAwaitsTheFuture(): Unit = runBlocking {
    val pending = CompletableFuture<ClassifierResponse>()
    val seen = mutableListOf<ClassifierRequest>()
    val classifier = futureClassifier { request ->
      seen += request
      pending
    }

    val call = async(start = CoroutineStart.UNDISPATCHED) { classifier.classify(sampleRequest) }

    assertThat(seen).containsExactly(sampleRequest)
    assertThat(call.isCompleted).isFalse()
    pending.complete(cannedResponse)
    assertThat(call.await()).isEqualTo(cannedResponse)
  }

  @Test
  fun classify_failedFuture_throwsTheCause(): Unit = runBlocking {
    val classifier = futureClassifier {
      CompletableFuture<ClassifierResponse>().apply {
        completeExceptionally(IllegalArgumentException("bad input"))
      }
    }

    val thrown = assertFailsWith<IllegalArgumentException> { classifier.classify(sampleRequest) }

    assertThat(thrown).hasMessageThat().isEqualTo("bad input")
  }

  @Test
  fun classify_canceledFuture_cancelsTheCall(): Unit = runBlocking {
    val pending = CompletableFuture<ClassifierResponse>()
    val classifier = futureClassifier { pending }
    val call = async(start = CoroutineStart.UNDISPATCHED) { classifier.classify(sampleRequest) }

    pending.cancel(true)

    assertFailsWith<CancellationException> { call.await() }
  }

  @Test
  fun classify_canceledCall_cancelsTheFuture(): Unit = runBlocking {
    val pending = CompletableFuture<ClassifierResponse>()
    val classifier = futureClassifier { pending }
    val call = async(start = CoroutineStart.UNDISPATCHED) { classifier.classify(sampleRequest) }

    call.cancelAndJoin()

    assertThat(pending.isCancelled).isTrue()
  }

  private fun futureClassifier(
    name: String = "m",
    block: (ClassifierRequest) -> CompletableFuture<ClassifierResponse>,
  ): BaseFutureClassifier =
    object : BaseFutureClassifier(name) {
      override fun classifyAsync(
        request: ClassifierRequest
      ): CompletableFuture<ClassifierResponse> = block(request)
    }
}
