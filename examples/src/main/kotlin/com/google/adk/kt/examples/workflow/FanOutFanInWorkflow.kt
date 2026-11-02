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
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `fan_out_fan_in` workflow sample: three nodes transform the user's text
 * concurrently, and a [JoinNode] waits for all of them before one node combines their outputs.
 */
object FanOutFanInWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow =
    workflow("fan_out_fan_in") {
      // START hands each branch the user's message, which a String input receives as text.
      Start.then(makeUppercase, countCharacters, reverseString)
        .joinTo(JoinNode("join_for_results"))
        .then(aggregate)
    }

  private val makeUppercase = node<String, String>("make_uppercase") { _, text -> text.uppercase() }

  private val countCharacters = node<String, Int>("count_characters") { _, text -> text.length }

  private val reverseString = node<String, String>("reverse_string") { _, text -> text.reversed() }

  /** Combines the three outputs, which the join passes on keyed by node name. */
  private val aggregate =
    node<Map<String, Any?>, Unit>("aggregate") { _, results ->
      val message =
        "Uppercase: ${results["make_uppercase"]}\n\n" +
          "Character Count: ${results["count_characters"]}\n\n" +
          "Reversed: ${results["reverse_string"]}\n\n"
      emit(Content.fromText(Role.MODEL, message))
    }
}

/** Runs the workflow on one message and prints the combined result. */
fun main() = runBlocking {
  val runner =
    InMemoryRunner(app = App(appName = "fan_out_fan_in", rootNode = FanOutFanInWorkflow.create()))
  val message = Content.fromText(Role.USER, "Hello World")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println(text)
  }
}
