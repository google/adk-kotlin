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

package com.google.adk.kt.events

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.processors.generateRequestConfirmationEvent
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EventTest {

  @Test
  fun generateRequestConfirmationEvent_returnsEventWithConfirmations() = runTest {
    val callId = "call_abc"
    val originalCall = FunctionCall(name = "tool_x", args = mapOf("arg" to "val"), id = callId)
    val event =
      Event(author = "model", content = Content(parts = listOf(Part(functionCall = originalCall))))
    val actions = EventActions()
    actions.requestedToolConfirmations[callId] =
      ToolConfirmation(confirmed = true, payload = "p", hint = "h")
    val functionResponseEvent = Event(author = "system", actions = actions)
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session"))
    val context =
      InvocationContext(
        session = session,
        runConfig = null,
        agent = DummyAgent("agent"),
        invocationId = "session",
      )

    val confirmationEvent = generateRequestConfirmationEvent(context, event, functionResponseEvent)

    assertNotNull(confirmationEvent)
    assertEquals("agent", confirmationEvent.author)
    assertEquals("session", confirmationEvent.invocationId)
    val content = confirmationEvent.content
    assertNotNull(content)
    assertEquals(1, content.parts.size)
    val call = content.parts[0].functionCall!!
    assertEquals(FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME, call.name)
    val originalArg = call.args[FunctionCall.ORIGINAL_FUNCTION_CALL_KEY] as Map<*, *>
    assertEquals("tool_x", originalArg["name"])
    assertEquals(callId, originalArg["id"])
    val confArg = call.args[FunctionCall.TOOL_CONFIRMATION_KEY] as Map<*, *>
    assertEquals(true, confArg["confirmed"])
    assertEquals("p", confArg["payload"])
    assertEquals("h", confArg["hint"])
    assertTrue(confirmationEvent.longRunningToolIds.contains(call.id))
  }

  @Test
  fun populateClientFunctionCallId_assignsIds() {
    val callWithoutId = FunctionCall(name = "tool1")
    val event =
      Event(author = "model", content = Content(parts = listOf(Part(functionCall = callWithoutId))))

    val updatedEvent = event.populateClientFunctionCallId()

    val call = updatedEvent.content!!.parts[0].functionCall!!
    val callId = call.id
    assertNotNull(callId)
    assertTrue(callId.startsWith(FunctionCall.ADK_FUNCTION_CALL_ID_PREFIX))
  }

  @Test
  fun generateRequestConfirmationEvent_preservesActions() = runTest {
    val actions = EventActions()
    val toolConfirmation = ToolConfirmation(confirmed = false)
    actions.requestedToolConfirmations["call_1"] = toolConfirmation

    val functionCall = FunctionCall(name = "tool1", id = "call_1")
    val actionEvent =
      Event(author = "agent", content = Content(parts = listOf(Part(functionCall = functionCall))))

    val responseEvent = Event(author = "agent", actions = actions)

    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session"))
    val context =
      InvocationContext(session = session, runConfig = null, agent = DummyAgent("agent"))

    val confirmationEvent = generateRequestConfirmationEvent(context, actionEvent, responseEvent)

    assertNotNull(confirmationEvent)
    assertEquals(actions, confirmationEvent.actions)
  }

  @Test
  fun contentText_joinsContentTextPartsWithSeparator() {
    val event =
      Event(
        author = "model",
        content = Content(parts = listOf(Part(text = "Hello"), Part(text = "world"))),
      )

    assertEquals("Hello world", event.contentText(" "))
  }

  @Test
  fun contentText_defaultSeparatorIsEmpty() {
    val event =
      Event(
        author = "model",
        content = Content(parts = listOf(Part(text = "Hel"), Part(text = "lo"))),
      )

    assertEquals("Hello", event.contentText())
  }

  @Test
  fun contentText_emptyWhenNoContent() {
    assertEquals("", Event(author = "model").contentText(" "))
  }

  @Test
  fun contentText_excludesThoughtPartsUnlessRequested() {
    val event =
      Event(
        author = "model",
        content = Content(parts = listOf(Part(text = "hi"), Part(text = "hmm", thought = true))),
      )

    assertEquals("hi", event.contentText(" "))
    assertEquals("hi hmm", event.contentText(" ", includeThoughts = true))
  }
}
