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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `multi_triggers` workflow sample: three nodes transform the user's text
 * concurrently and each one triggers the same node, which therefore runs three times.
 */
object MultiTriggersWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow =
    workflow("multi_triggers") {
      // START hands each branch the user's message as Content.
      Start.then(nodes(makeUppercase, countCharacters, reverseString))
      // With no JoinNode in between, each predecessor triggers its own run of send_message.
      makeUppercase.then(sendMessage)
      countCharacters.then(sendMessage)
      reverseString.then(sendMessage)
    }

  private val makeUppercase =
    node<Content, String>("make_uppercase") { _, message -> message.text().uppercase() }

  private val countCharacters =
    node<Content, Int>("count_characters") { _, message -> message.text().length }

  private val reverseString =
    node<Content, String>("reverse_string") { _, message -> message.text().reversed() }

  /** Reports the output of whichever predecessor triggered this run. */
  private val sendMessage =
    node<Any?, Content>("send_message") { _, input ->
      Content.fromText(Role.MODEL, "Triggered for input: $input")
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
