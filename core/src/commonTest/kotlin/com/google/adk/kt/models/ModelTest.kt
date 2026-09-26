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
package com.google.adk.kt.models

import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.types.Content
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * Covers the optional-capability default on [Model.connect], and the blocking
 * [LiveConnection.close] bridge that runs [LiveConnection.closeSession].
 */
class ModelTest {

  /** Holds no transport; this test only needs to count teardowns. */
  private class CountingLiveConnection : LiveConnection {
    var closeSessionCalls = 0

    override suspend fun sendHistory(history: List<Content>) {}

    override suspend fun sendContent(content: Content, partial: Boolean) {}

    override suspend fun sendRealtime(input: RealtimeInput) {}

    override fun receive(): Flow<LlmResponse> = emptyFlow()

    override suspend fun closeSession() {
      closeSessionCalls++
    }
  }

  @Test
  fun connect_modelWithoutLiveSupport_throwsNamingTheModel(): Unit = runBlocking {
    val failure =
      assertFailsWith<UnsupportedOperationException> {
        DummyModel("non-live-model").connect(LlmRequest())
      }

    assertContains(
      failure.message.orEmpty(),
      "non-live-model",
      message = "the refusal must name the model so a caller knows which one lacks live support",
    )
  }

  @Test
  fun close_onALiveConnection_runsCloseSessionOnce() {
    val connection = CountingLiveConnection()

    connection.close()

    assertEquals(1, connection.closeSessionCalls)
  }
}
