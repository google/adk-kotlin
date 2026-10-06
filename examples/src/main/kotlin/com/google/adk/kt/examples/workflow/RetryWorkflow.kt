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
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.RetryConfig
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * A port of adk-python's `retry` workflow sample: a weather lookup fails at random, and its retry
 * policy runs it again, up to five attempts in all, before the next node reports the weather. Each
 * failed attempt emits an error event; the run fails if every attempt does.
 */
object RetryWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow = workflow("retry") { chain(Start, getWeather, reportWeather) }

  /** A mock weather lookup that fails 70% of the time, retried with backoff from one second. */
  private val getWeather =
    node<Any?, String>(
      "get_weather",
      config = NodeConfig(retryConfig = RetryConfig(maxAttempts = 5, initialDelay = 1.seconds)),
    ) { context, _ ->
      val attempt = "Getting weather... attempt ${context.attemptCount}"
      emit(Event(content = Content.fromText(Role.MODEL, attempt)))
      if (Random.nextDouble() < 0.7) throw HttpException(500, "Internal Server Error")
      "sunny"
    }

  /** Reports the weather the lookup returned. */
  private val reportWeather =
    node<String, Content>("report_weather") { _, weather ->
      Content.fromText(Role.MODEL, "The weather is $weather")
    }
}

/** Runs the workflow once and prints each attempt, each failure, and the report. */
fun main() = runBlocking {
  val runner = InMemoryRunner(app = App(appName = "retry", rootNode = RetryWorkflow.create()))
  val message = Content.fromText(Role.USER, "go")
  try {
    runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
      val text = event.contentText(" ")
      if (text.isNotBlank()) println(text)
      event.errorCode?.let { println("$it: ${event.errorMessage}") }
    }
  } catch (e: HttpException) {
    // When the last attempt fails too, the run fails with that attempt's error.
    println("Every attempt failed.")
  }
}

/** A mock API's error response. */
private class HttpException(code: Int, reason: String) : Exception("HTTP Error $code: $reason")
