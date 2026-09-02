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

package com.google.adk.kt.examples.longrunning

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.apps.App
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

private const val APP_NAME = "nav_value_app"

/**
 * Runnable demo of a long-running tool that returns a **value** in a **resumable** app
 * ([ResumabilityConfig] with `isResumable = true`).
 *
 * This is the counterpart of [ResumableLongRunningToolDemoAgent] (which defers with `Unit`): here
 * [ChangeDestinationTool] answers the dispatch with a placeholder value, so the call is resolved
 * and the model is re-invoked to summarize it in the **same turn** (two model calls) instead of
 * pausing. The `endOfAgent` marker is still suppressed, so the invocation stays live and the
 * device's real result can be delivered later by a resume.
 */
fun main() = runBlocking {
  val model = ScriptedNavModel()
  val agent =
    LlmAgent(
      name = "nav_agent",
      model = model,
      instruction =
        Instruction("Help the driver navigate. Use $CHANGE_DESTINATION_TOOL to reroute them."),
      tools = listOf(ChangeDestinationTool(respondImmediately = true)),
    )
  val runner =
    InMemoryRunner(
      App(
        appName = APP_NAME,
        rootAgent = agent,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    )

  println("=== Resumable long-running tool demo (value return continues) ===")
  println("User > Change my destination to $REQUESTED_DESTINATION.")

  val turn1 =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = Content.fromText(Role.USER, "Change my destination to $REQUESTED_DESTINATION."),
      )
      .toList()
  printEvents("turn 1 (value answers the call; the model summarizes, invocation stays live)", turn1)
  println(
    "   model invocations during turn 1: ${model.invocations} " +
      "(the value answered the call, so the model is re-invoked to summarize in the same turn)"
  )

  val resumableCall = turn1.pausedLongRunningCall()
  val invocationId = turn1.firstOrNull()?.invocationId
  if (resumableCall == null || invocationId == null) {
    println("No live long-running call; nothing to resume.")
    return@runBlocking
  }
  println(
    "   summarized but still resumable on ${resumableCall.name} " +
      "(callId=${resumableCall.id}, invocationId=$invocationId)"
  )

  println(
    "[app] device applied the destination; delivering the real result to invocation $invocationId."
  )
  val turn2 =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        invocationId = invocationId,
        newMessage = deviceResult(resumableCall),
      )
      .toList()
  printEvents("turn 2 (resumed by invocationId with the device result)", turn2)

  val session =
    runner.sessionService.getSession(SessionKey(APP_NAME, DEMO_USER_ID, DEMO_SESSION_ID))
  println("Stored session now has ${session?.events?.size ?: 0} events.")
}
