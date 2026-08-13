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

package com.google.adk.kt.runners

import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import java.util.function.Consumer
import kotlinx.coroutines.runBlocking

/**
 * Java-friendly blocking wrapper around a [Runner]. Each call blocks the calling thread until the
 * agent turn completes, delivering each [Event] to [onEvent] as it is produced, so Java callers can
 * consume the run without collecting a `Flow`. Call these from ordinary (non-coroutine) threads.
 */
class BlockingRunner(private val delegate: Runner) {

  /** Runs a turn with a plain-text user message, delivering each event to [onEvent]. */
  @JvmOverloads
  fun run(
    userId: String,
    sessionId: String,
    message: String,
    onEvent: Consumer<Event>,
    runConfig: RunConfig? = null,
  ): Unit = run(userId, sessionId, Content.fromText(Role.USER, message), onEvent, runConfig)

  /** Runs a turn with a prepared [newMessage], delivering each event to [onEvent]. */
  @JvmOverloads
  fun run(
    userId: String,
    sessionId: String,
    newMessage: Content,
    onEvent: Consumer<Event>,
    runConfig: RunConfig? = null,
  ): Unit = runBlocking {
    delegate
      .runAsync(
        userId = userId,
        sessionId = sessionId,
        newMessage = newMessage,
        runConfig = runConfig,
      )
      .collect { onEvent.accept(it) }
  }
}
