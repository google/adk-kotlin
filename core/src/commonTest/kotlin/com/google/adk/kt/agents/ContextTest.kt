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

package com.google.adk.kt.agents

import com.google.adk.kt.events.EventActions
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.ToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests the unified [Context] behavior shared across callback and tool execution: copy-on-write
 * state updates, a committed-only readonly view (`context.context`), and both callback and tool
 * properties on the same class.
 *
 * Existing `CallbackContextTest` and `ToolContextTest` suites cover per-type behavior through the
 * [CallbackContext] and [ToolContext] subclasses.
 */
class ContextTest {

  // Locks in CallbackContext behavior: a callback context exposes the agent and delta-aware state.
  @Test
  fun callbackFlavor_exposesAgentAndMergedState() {
    val agent = DummyAgent(name = "callback-agent")
    val context = Context(testInvocationContext(agent = agent))

    context.updateState("key", "value")

    assertEquals(agent, context.agent)
    assertEquals("value", context.state["key"])
    assertNull(context.functionCallId)
  }

  // Locks in CallbackContext.updateState: copy-on-write that leaves a caller-supplied EventActions
  // untouched (updateState was a callback-only operation before unification).
  @Test
  fun updateState_replacesActionsWithoutMutatingTheOriginal() {
    val original = EventActions()
    val context = Context(testInvocationContext(), original)

    context.updateState("key", "value")

    // Copy-on-write: the EventActions passed in is left untouched.
    assertNull(original.stateDelta["key"])
    // The write is visible through the current actions and the delta-aware state view.
    assertEquals("value", context.actions.stateDelta["key"])
    assertEquals("value", context.eventActions.stateDelta["key"])
    assertEquals("value", context.state["key"])
  }

  // Locks in CallbackContext.mergeEventActions: swaps the EventActions object while keeping every
  // write (mergeEventActions was reachable only from the before-agent callback pipeline).
  @Test
  fun mergeEventActions_replacesTheObjectAndKeepsEveryWrite() {
    val original = EventActions()
    val context = Context(testInvocationContext(), original)

    context.updateState("before", "1")
    context.mergeEventActions(EventActions(stateDelta = mutableMapOf("merged" to "2")))
    context.updateState("after", "3")

    // mergeEventActions replaces the EventActions instance, so earlier references do not see new
    // writes.
    assertNotSame(original, context.actions)
    assertNull(original.stateDelta["after"])
    // All writes before, during, and after the merge are preserved in the new actions instance.
    assertEquals("1", context.actions.stateDelta["before"])
    assertEquals("2", context.actions.stateDelta["merged"])
    assertEquals("3", context.actions.stateDelta["after"])
    assertEquals("3", context.state["after"])
  }

  // Locks in ToolContext.context: a fresh, committed-only readonly view (the `.context` member
  // comes from ReadonlyToolContext, which only the tool flavor had before unification).
  @Test
  fun context_isACommittedOnlyReadonlyView() {
    val context = Context(testInvocationContext())

    context.updateState("pending", "value")

    // context.context is a readonly view of committed session state; it excludes the pending delta
    // that context.state carries.
    assertEquals("value", context.state["pending"])
    assertNull(context.context.state["pending"])
  }

  // Unification surface (not a status-quo lock-in): a tool context (functionCallId set) now also
  // exposes the callback writes (state, updateState) that the pre-unification ToolContext lacked.
  @Test
  fun toolFlavor_alsoCarriesTheCallbackWrites() {
    val context = Context(testInvocationContext(), functionCallId = "fc-1", eventId = "evt-1")

    context.updateState("key", "value")

    assertEquals("fc-1", context.functionCallId)
    assertEquals("evt-1", context.eventId)
    assertEquals("value", context.state["key"])
    assertEquals("value", context.actions.stateDelta["key"])
  }

  // Locks in CallbackContext's constructor parameter names (invocationContext, eventActions) for
  // source compatibility.
  @Test
  fun callbackContext_keepsItsOriginalConstructorParameterNames() {
    // Call using named arguments so compilation fails if parameter names change. Preserving the
    // original parameter names ensures source compatibility for existing callers; ToolContext's
    // constructor parameters are covered by ToolContextTest.
    val actions = EventActions()

    val context =
      CallbackContext(invocationContext = testInvocationContext(), eventActions = actions)

    assertSame(actions, context.actions)
  }

  // Covers both subclasses: CallbackContext and ToolContext resolve to the same unified Context.
  @Test
  fun bothSubclasses_areContexts_andShareItsBehavior() {
    val callbackContext: Context = CallbackContext(testInvocationContext())
    val toolContext: Context = ToolContext(testInvocationContext(), functionCallId = "fc-1")

    callbackContext.updateState("from-callback", "1")
    toolContext.updateState("from-tool", "2")

    // Both subclasses delegate to the same unified Context implementation.
    assertEquals("1", callbackContext.state["from-callback"])
    assertEquals("2", toolContext.state["from-tool"])
    // Both subclasses expose the full Context API surface.
    assertNull(callbackContext.functionCallId)
    assertEquals("fc-1", toolContext.functionCallId)
  }
}
