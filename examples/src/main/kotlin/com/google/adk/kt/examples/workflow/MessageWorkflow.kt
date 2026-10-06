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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.examples.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.workflow
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `message` workflow sample: nodes send the user a text message, a message
 * with an inline image, several messages in a row, and a sentence streamed in chunks.
 */
object MessageWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow =
    workflow("message") {
      chain(Start, SendString(), SendMultimodal(), MultipleMessages(), StreamSentence())
    }
}

/** Runs the workflow once and prints each complete message. */
fun main() = runBlocking {
  val runner = InMemoryRunner(app = App(appName = "message", rootNode = MessageWorkflow.create()))
  val message = Content.fromText(Role.USER, "go")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (!event.partial && text.isNotBlank()) println(text)
  }
}

/** A 16x16 solid red PNG. */
private const val RED_SQUARE_PNG =
  "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAXElEQVR4nO2TSQ7AIAwD" +
    "7fz/z+ZQtapwmrJc8QklmjBIgZJgIZMiAIl9KYbhjx4fgwosbNxgMrF0+4uhgHnYDM6" +
    "AzQHJeg5HYtyHFfgy2AztN/5tZWfrBtVzkl4DzfQkEPd+cEkAAAAASUVORK5CYII="

/** A user-facing message from a node, with [parts] as its content. */
private fun message(vararg parts: Part) =
  Event(author = "", content = Content(role = Role.MODEL, parts = parts.toList()))

private class SendString : Node {
  override val name = "send_string"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(message(Part(text = "#1 This is a simple string message.")))
  }
}

private class SendMultimodal : Node {
  override val name = "send_multimodal"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val image = Blob(mimeType = "image/png", data = Base64.getDecoder().decode(RED_SQUARE_PNG))
    emit(
      message(
        Part(text = "#2 Here is a multi-modal message with an inline image (red square):"),
        Part(inlineData = image),
      )
    )
  }
}

/** Sends complete messages one after another from the same node. */
private class MultipleMessages : Node {
  override val name = "multiple_messages"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    for (text in listOf("#3 Multiple messages", "Processing step 1...", "Processing step 2...")) {
      emit(message(Part(text = text)))
      delay(1.seconds)
    }
    emit(message(Part(text = "Done processing.")))
  }
}

/**
 * Streams a sentence in partial chunks, which clients display but the session does not store, so
 * the node then sends the whole sentence once as a complete message.
 */
private class StreamSentence : Node {
  override val name = "stream_sentence"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(message(Part(text = "#4 Starting to stream...")))
    val sentence =
      """
      This is a streaming message sent in chunks.

      You can stream in markdown as well. For example, the table below:

      | Header 1 | Header 2 |
      |----------|----------|
      | Cell 1   | Cell 2   |
      | Cell 3   | Cell 4   |
      """
        .trimIndent()
    for (chunk in sentence.chunked(5)) {
      emit(message(Part(text = chunk)).copy(partial = true))
      delay(200.milliseconds)
    }
    emit(message(Part(text = sentence)))
  }
}
