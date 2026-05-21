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

package com.google.adk.kt.testing

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class DummyModelTest {

  @Test
  fun generateContent_withSequentialFlows_returnsFlowsInOrder() = runTest {
    val response1 = LlmResponse(content = modelMessage("first"))
    val response2 = LlmResponse(content = modelMessage("second"))
    val model = DummyModel("test", listOf(flowOf(response1), flowOf(response2)))

    val result1 = model.generateContent(LlmRequest()).toList()
    val result2 = model.generateContent(LlmRequest()).toList()
    val result3 = model.generateContent(LlmRequest()).toList()

    assertEquals(listOf(response1), result1)
    assertEquals(listOf(response2), result2)
    assertEquals(emptyList<LlmResponse>(), result3)
  }

  @Test
  fun createSequential_returnsResponsesInOrder() = runTest {
    val response1 = LlmResponse(content = modelMessage("first"))
    val response2 = LlmResponse(content = modelMessage("second"))
    val model = DummyModel.createSequential("test", listOf(response1, response2))

    val result1 = model.generateContent(LlmRequest()).toList()
    val result2 = model.generateContent(LlmRequest()).toList()

    assertEquals(listOf(response1), result1)
    assertEquals(listOf(response2), result2)
  }
}
