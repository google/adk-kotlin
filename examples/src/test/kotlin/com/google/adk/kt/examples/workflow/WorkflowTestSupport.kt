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
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Workflow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Which implementation of a workflow example a parameterized test runs. */
enum class Port {
  KOTLIN,
  JAVA,
}

/**
 * What one run of a workflow sent, when each event reached the caller, and the exception the run
 * failed with, if any.
 */
internal class WorkflowRun(
  val events: List<Event>,
  val arrivals: List<TimeSource.Monotonic.ValueTimeMark>,
  val failure: Exception?,
) {
  /** The text of each complete message, in order. */
  val messages: List<String> =
    events.filter { !it.partial }.mapNotNull { it.contentText().ifEmpty { null } }

  /** Each output, paired with the name of the node that sent it, in order. */
  val outputs: List<Pair<String?, Any>> = events.mapNotNull { event ->
    event.output?.let { event.nodeName to it }
  }
}

/** Runs [workflow] once on a user message with [text], in a new in-memory session. */
internal suspend fun runWorkflow(workflow: Workflow, text: String): WorkflowRun {
  val runner = InMemoryRunner(app = App(appName = "examples", rootNode = workflow))
  val message = Content.fromText(Role.USER, text)
  val events = mutableListOf<Event>()
  val arrivals = mutableListOf<TimeSource.Monotonic.ValueTimeMark>()
  val failure =
    try {
      runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect {
        events += it
        arrivals += TimeSource.Monotonic.markNow()
      }
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e
    }
  return WorkflowRun(events, arrivals, failure)
}
