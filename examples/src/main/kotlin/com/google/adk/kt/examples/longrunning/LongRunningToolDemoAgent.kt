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
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Runnable end-to-end demo of a long-running, client-side tool in a **non-resumable** app (the
 * default [InMemoryRunner] configuration).
 *
 * Flow:
 * 1. The user asks the agent to change destination; the model calls [ChangeDestinationTool].
 * 2. The tool is long-running and returns a placeholder, so the agent dispatches the action and the
 *    turn ends. Because the app is not resumable, the framework re-invokes the model on the
 *    placeholder within the same turn, producing an interim reply (watch the model-invocation count
 *    and the extra `text` event in turn 1).
 * 3. The device reports the real result; a fresh `runAsync` (a new invocation) delivers it as a
 *    `FunctionResponse` and the model produces the final answer. The tool is not re-run.
 *
 * Compare with `ResumableLongRunningToolDemoAgent.kt`, which pauses immediately instead.
 */
fun main() = runBlocking {
  val model = ScriptedNavModel()
  val agent =
    LlmAgent(
      name = "nav_agent",
      model = model,
      instruction =
        Instruction("Help the driver navigate. Use $CHANGE_DESTINATION_TOOL to reroute them."),
      tools = listOf(ChangeDestinationTool()),
    )
  val runner = InMemoryRunner(agent = agent)

  println("=== Non-resumable long-running tool demo ===")
  println("User > Change my destination to $REQUESTED_DESTINATION.")

  val turn1 =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = Content.fromText(Role.USER, "Change my destination to $REQUESTED_DESTINATION."),
      )
      .toList()
  printEvents("turn 1 (agent dispatches the client action)", turn1)
  println(
    "   model invocations during turn 1: ${model.invocations} " +
      "(non-resumable re-invokes the model on the placeholder response)"
  )

  val pausedCall = turn1.pausedLongRunningCall()
  if (pausedCall == null) {
    println("No long-running call was produced; nothing to resume.")
    return@runBlocking
  }
  println("   paused on ${pausedCall.name} (callId=${pausedCall.id})")

  println("[app] destination applied on the device; returning the result to the agent.")
  val turn2 =
    runner
      .runAsync(
        userId = DEMO_USER_ID,
        sessionId = DEMO_SESSION_ID,
        newMessage = deviceResult(pausedCall),
      )
      .toList()
  printEvents("turn 2 (resumed with the device result)", turn2)

  val session =
    runner.sessionService.getSession(SessionKey("InMemoryRunner", DEMO_USER_ID, DEMO_SESSION_ID))
  println("Stored session now has ${session?.events?.size ?: 0} events.")
}
