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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.capturingLogs
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class LiveStreamingToolsLoggingTest {

  @Test
  fun run_toolYieldsEventWithoutContent_warnsThatNothingIsDelivered(): Unit = runBlocking {
    val emitted = CompletableDeferred<Unit>()
    val tool =
      DummyTool(name = "monitor") { _, _ ->
        flow {
          emit(Event())
          emitted.complete(Unit)
        }
      }

    val (delivered, records) =
      capturingLogs(LOGGER, Level.WARNING) {
        streaming(tool) { tools ->
          emitted.await()
          tools.drainEvents()
        }
      }

    assertEquals(emptyList(), delivered)
    assertEquals(
      "Streaming tool `monitor` yielded an Event that is not a purely user-facing message: it " +
        "has no content, so there is nothing to deliver. To send a message, use Event(content=...)",
      records.single().message,
    )
  }

  @Test
  fun run_toolYieldsEventWithMoreThanAMessage_warnsAndDeliversTheMessage(): Unit = runBlocking {
    val tool =
      DummyTool(name = "monitor") { _, _ ->
        flow { emit(Event(content = Content.fromText(Role.MODEL, "hi"), turnComplete = true)) }
      }

    val (delivered, records) =
      capturingLogs(LOGGER, Level.WARNING) {
        streaming(tool) { tools ->
          val toolEvent = tools.nextEvent()
          toolEvent.handled.complete(Unit)
          toolEvent.event
        }
      }

    assertEquals("hi", delivered.content?.text())
    assertEquals(
      "Streaming tool `monitor` yielded an Event that is not a purely user-facing message: it " +
        "sets fields beyond the message, which are ignored. To send a message, use " +
        "Event(content=...)",
      records.single().message,
    )
  }

  /** Starts [tool]'s stream as a live run does, runs [block], then closes the run's streams. */
  private suspend fun <T> streaming(tool: DummyTool, block: suspend (LiveStreamingTools) -> T): T {
    val tools =
      LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", currentCoroutineContext())
    try {
      val context =
        ToolContext(
          invocationContext =
            InvocationContext(
              session = Session(key = SessionKey("app", "user", "s")),
              agent = LlmAgent(name = "agent", model = DummyModel("model")),
            ),
          functionCallId = "c1",
        )
      val unused = tools.run(tool, context, emptyMap())
      val pending = FunctionResponse(name = tool.name, id = "c1", response = emptyMap())
      tools.onAnswersQueued(Content(Role.USER, listOf(Part(functionResponse = pending))))
      return block(tools)
    } finally {
      withContext(NonCancellable) { tools.close() }
    }
  }

  companion object {
    private val LOGGER: String = LiveStreamingTools::class.java.name
  }
}
