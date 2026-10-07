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

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * A [Model] supporting live connections, handing out [FakeLiveConnection]s playing [script].
 *
 * This is what lets a test drive a whole live run - queue, agent, turn loop - without a network. A
 * new connection is produced per [connect], and [connections] records them in order so a test can
 * assert on what the agent sent.
 *
 * @param script Played by every connection this model opens.
 * @param name The model's identifier, which ADK reads as it reads a real model's name, for example
 *   to decide whether an output schema can be combined with tools.
 */
@ExperimentalLiveApi
class FakeLiveModel
@JvmOverloads
constructor(private val script: LiveScript, override val name: String = "fake-live-model") : Model {

  // One StateFlow keeps requests and connections index-aligned under concurrent connect().
  private val openedState =
    MutableStateFlow<List<Pair<LlmRequest, FakeLiveConnection>>>(emptyList())

  /** Every connection opened so far, oldest first. */
  val connections: List<FakeLiveConnection>
    get() = openedState.value.map { it.second }

  /** The requests passed to [connect], oldest first. */
  val connectRequests: List<LlmRequest>
    get() = openedState.value.map { it.first }

  override suspend fun connect(request: LlmRequest): LiveConnection {
    val connection = FakeLiveConnection(script)
    openedState.update { it + (request to connection) }
    return connection
  }

  /** Not part of the live path; a live test that reaches this is misconfigured. */
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    throw AssertionError("FakeLiveModel serves only connect()")
  }
}
