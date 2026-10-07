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
@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.common.truth.Truth.assertThat
import java.util.IdentityHashMap
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Tests [FakeLiveModel], which hands out a fresh scripted connection per connect. */
class FakeLiveModelTest {

  @Test
  fun connect_eachCall_opensANewConnectionAndRecordsItInOrder(): Unit = runBlocking {
    val model = FakeLiveModel(LiveScript.builder().text("hi").build())
    val first = LlmRequest(model = model)
    val second = LlmRequest()

    val a = model.connect(first)
    val b = model.connect(second)

    assertThat(a).isNotSameInstanceAs(b)
    assertThat(model.connections).containsExactly(a, b).inOrder()
    assertThat(model.connectRequests).containsExactly(first, second).inOrder()
  }

  @Test
  fun connect_concurrentCalls_recordEveryConnectionAndRequest() {
    val model = FakeLiveModel(LiveScript.builder().endStream().build())
    val coroutines = 8
    val perCoroutine = 2000
    val total = coroutines * perCoroutine

    val pairs =
      runBlocking(Dispatchers.Default) {
        (1..coroutines)
          .map {
            async {
              List(perCoroutine) {
                val request = LlmRequest()
                request to model.connect(request)
              }
            }
          }
          .awaitAll()
          .flatten()
      }

    // Unsynchronized recording would drop entries under this contention; every connect is recorded.
    assertThat(pairs).hasSize(total)
    assertThat(model.connections).containsExactlyElementsIn(pairs.map { it.second })
    assertThat(model.connectRequests).hasSize(total)
    // Identity, not equality: every LlmRequest() here is equal, and the lists must align by index.
    val requestByConnection = IdentityHashMap<LiveConnection, LlmRequest>()
    for ((request, connection) in pairs) requestByConnection[connection] = request
    for (i in model.connections.indices) {
      assertThat(model.connectRequests[i])
        .isSameInstanceAs(requestByConnection[model.connections[i]])
    }
  }

  @Test
  fun generateContent_reachedFromALiveTest_failsLoudly(): Unit = runBlocking {
    val model = FakeLiveModel(LiveScript.builder().build())

    assertFailsWith<AssertionError> { model.generateContent(LlmRequest(), stream = false).toList() }
  }
}
