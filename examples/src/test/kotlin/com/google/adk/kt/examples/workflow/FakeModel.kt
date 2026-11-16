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
package com.google.adk.kt.examples.workflow

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A model that answers each request with the text [reply] returns, and records the requests. */
internal class FakeModel(private val reply: (LlmRequest) -> String) : Model {
  override val name = "fake-model"

  // Agents in parallel branches add to it concurrently.
  private val _requests = CopyOnWriteArrayList<LlmRequest>()

  /** The requests received so far. */
  val requests: List<LlmRequest>
    get() = _requests

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    _requests += request
    emit(LlmResponse(content = Content.fromText(Role.MODEL, reply(request))))
  }
}
