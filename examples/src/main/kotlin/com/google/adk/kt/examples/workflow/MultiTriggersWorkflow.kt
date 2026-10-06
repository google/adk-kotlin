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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `multi_triggers` workflow sample: three nodes transform the user's text
 * concurrently and each one triggers the same node, which therefore runs three times.
 */
object MultiTriggersWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow {
    val makeUppercase = MakeUppercase()
    val countCharacters = CountCharacters()
    val reverseString = ReverseString()
    val sendMessage = SendMessage()

    return workflow("multi_triggers") {
      // START hands each branch the user's message as Content.
      Start.then(nodes(makeUppercase, countCharacters, reverseString))
      // With no JoinNode in between, each predecessor triggers its own run of send_message.
      makeUppercase.then(sendMessage)
      countCharacters.then(sendMessage)
      reverseString.then(sendMessage)
    }
  }
}

/** Runs the workflow on one message and prints the three reports. */
fun main() = runBlocking {
  val runner =
    InMemoryRunner(app = App(appName = "multi_triggers", rootNode = MultiTriggersWorkflow.create()))
  val message = Content.fromText(Role.USER, "Hello, workflows!")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println(text)
  }
}

private class MakeUppercase : Node {
  override val name = "make_uppercase"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit((nodeInput as Content).text().uppercase())
  }
}

private class CountCharacters : Node {
  override val name = "count_characters"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit((nodeInput as Content).text().length)
  }
}

private class ReverseString : Node {
  override val name = "reverse_string"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit((nodeInput as Content).text().reversed())
  }
}

/** Reports the output of whichever predecessor triggered this run. */
private class SendMessage : Node {
  override val name = "send_message"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val text = "Triggered for input: $nodeInput"
    emit(Event(author = "", content = Content.fromText(Role.MODEL, text)))
  }
}
