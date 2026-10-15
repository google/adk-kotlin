/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * A live agent that runs [block] and then holds the run open until the client leaves, unless
 * [holdOpen] is false, when the run ends on its own.
 */
internal class StubLiveAgent(
  name: String,
  private val holdOpen: Boolean = true,
  private val block: suspend FlowCollector<Event>.() -> Unit = {},
) : BaseAgent(name = name, description = "Stub live agent") {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()

  override fun runLiveImpl(context: InvocationContext): Flow<Event> = flow {
    block()
    if (holdOpen) awaitCancellation()
  }
}
