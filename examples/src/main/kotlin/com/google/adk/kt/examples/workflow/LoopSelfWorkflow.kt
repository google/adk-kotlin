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
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.workflow
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `loop_self` workflow sample: the user picks a number from 0 to 10, and a
 * node keeps routing back to itself until it guesses it.
 */
object LoopSelfWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow {
    val guessNumber = GuessNumber()

    return workflow("loop_self") {
      Start.then(ValidateInput()).then(guessNumber).thenRoute {
        "guessed_wrong" routesTo guessNumber
      }
    }
  }
}

/** Runs the workflow on one number and prints every guess. */
fun main() = runBlocking {
  val runner =
    InMemoryRunner(app = App(appName = "loop_self", rootNode = LoopSelfWorkflow.create()))
  val message = Content.fromText(Role.USER, "3")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println(text)
  }
}

/** Stores the user's number in state, and fails the run if it is out of range. */
private class ValidateInput : Node {
  override val name = "validate_input"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    // START hands the first node the user's message as Content.
    val number = (nodeInput as Content).text().trim().toInt()
    if (number !in 0..10) {
      val reply = "Please provide a number between 0 and 10."
      emit(Event(author = "", content = Content.fromText(Role.MODEL, reply)))
      throw IllegalArgumentException("Invalid input.")
    }
    context.updateState("target_number", number)
  }
}

/** Guesses a number, and routes back to itself when the guess is wrong. */
private class GuessNumber : Node {
  override val name = "guess_number"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val guess = Random.nextInt(0, 11)
    emit(Event(author = "", content = Content.fromText(Role.MODEL, "Guessing $guess...")))
    if (guess == (context.state["target_number"] as Number).toInt()) {
      emit(Event(author = "", content = Content.fromText(Role.MODEL, "Correct!")))
    } else {
      context.routes = listOf(Route.Tag("guessed_wrong"))
    }
  }
}
