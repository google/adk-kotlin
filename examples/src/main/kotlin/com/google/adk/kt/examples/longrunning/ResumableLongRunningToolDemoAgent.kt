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

private const val APP_NAME = "nav_app"

/**
 * Runnable end-to-end demo of a long-running, client-side tool in a **resumable** app
 * ([ResumabilityConfig] with `isResumable = true`).
 *
 * 1. The model calls [ChangeDestinationTool], which dispatches the action and returns `Unit` ("no
 *    response yet"), so no function-response event is emitted.
 * 2. The invocation pauses on the long-running function call -- the model is **not** re-invoked
 *    (one model call in turn 1) and the `endOfAgent` marker is suppressed so the invocation stays
 *    live.
 * 3. The device's real result resumes the paused invocation: a `FunctionResponse` carrying the call
 *    id is injected (its invocation id is passed too). Because the framework recomputes
 *    pause/resume purely from the stored session events, this resume can happen in a different
 *    process/server as long as the session is persisted.
 *
 * A long-running tool that instead returns a value answers the call and lets the model continue in
 * the same turn, in a resumable app too; see [LongRunningToolDemoAgent] for the non-resumable value
 * case.
 */
fun main() = runBlocking {
  val model = ScriptedNavModel()
  val agent =
    LlmAgent(
      name = "nav_agent",
      model = model,
      instruction =
        Instruction("Help the driver navigate. Use $CHANGE_DESTINATION_TOOL to reroute them."),
      tools = listOf(ChangeDestinationTool(respondImmediately = false)),
    )
  val runner =
    InMemoryRunner(
      App(
        appName = APP_NAME,
        rootAgent = agent,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    )

  println("=== Resumable long-running tool demo ===")
  println("User > Change my destination to $REQUESTED_DESTINATION.")

  val turn1 =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = Content.fromText(Role.USER, "Change my destination to $REQUESTED_DESTINATION."),
      )
      .toList()
  printEvents("turn 1 (agent pauses; invocation stays live)", turn1)
  println(
    "   model invocations during turn 1: ${model.invocations} " +
      "(the tool returned no response, so the model is not re-invoked and the invocation pauses)"
  )

  val pausedCall = turn1.pausedLongRunningCall()
  val invocationId = turn1.firstOrNull()?.invocationId
  if (pausedCall == null || invocationId == null) {
    println("No paused long-running call; nothing to resume.")
    return@runBlocking
  }
  println("   paused on ${pausedCall.name} (callId=${pausedCall.id}, invocationId=$invocationId)")

  println("[app] destination applied on the device; resuming invocation $invocationId.")
  val turn2 =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        invocationId = invocationId,
        newMessage = deviceResult(pausedCall),
      )
      .toList()
  printEvents("turn 2 (resumed by invocationId with the device result)", turn2)

  val session =
    runner.sessionService.getSession(SessionKey(APP_NAME, DEMO_USER_ID, DEMO_SESSION_ID))
  println("Stored session now has ${session?.events?.size ?: 0} events.")
}
