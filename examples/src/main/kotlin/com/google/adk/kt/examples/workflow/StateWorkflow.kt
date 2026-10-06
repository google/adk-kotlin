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
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `state` workflow sample: function nodes write session state through the
 * context and through an event, and read it back through the context. ADK's Kotlin function nodes
 * take no parameters from state, so `read_state_via_param` reads its key through the context too.
 */
object StateWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow =
    workflow("state_sample") {
      chain(Start, processInitialInput, updateStateViaEvent, readStateViaContext, readStateViaParam)
    }

  /** Stores the user's text in state through the context, and outputs it. */
  private val processInitialInput =
    node<Content, String>("process_initial_input") { context, message ->
      // START hands the first node the user's message as Content.
      val text = message.text()
      context.updateState("original_text", text)
      text
    }

  /** Stores the uppercased text by returning an event whose state delta the session applies. */
  private val updateStateViaEvent =
    node<String, Event>("update_state_via_event") { _, text ->
      Event(
        actions = EventActions(stateDelta = mutableMapOf("uppercased_text" to text.uppercase()))
      )
    }

  /** Reads both earlier values from state, then stores and outputs a sentence combining them. */
  private val readStateViaContext =
    node<Any?, String>("read_state_via_ctx") { context, _ ->
      val result =
        "${context.state["uppercased_text"]} (Original was: ${context.state["original_text"]})"
      context.updateState("appended_text", result)
      result
    }

  /** Outputs the final result built from the sentence the previous node stored. */
  private val readStateViaParam =
    node<Any?, String>("read_state_via_param") { context, _ ->
      "Final Result: ${context.state["appended_text"]}!"
    }
}

/** Runs the workflow on one message and prints each node's state changes and output. */
fun main() = runBlocking {
  val runner =
    InMemoryRunner(app = App(appName = "state_sample", rootNode = StateWorkflow.create()))
  val message = Content.fromText(Role.USER, "Hello ADK!")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val path = event.nodeInfo?.path
    if (event.actions.stateDelta.isNotEmpty()) println("[$path] state: ${event.actions.stateDelta}")
    event.output?.let { println("[$path] output: $it") }
  }
}
