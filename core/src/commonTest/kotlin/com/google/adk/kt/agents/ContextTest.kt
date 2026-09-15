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
 * Covers what the unified [Context] adds over the two classes it replaces: one shared actions
 * object, a readonly view of itself, and both flavors on a single instance. Per-flavor behavior
 * stays in `CallbackContextTest` and `ToolContextTest`, which now reach this class through the
 * aliases.
 */
class ContextTest {

  @Test
  fun callbackFlavor_exposesAgentAndMergedState() {
    val agent = DummyAgent(name = "callback-agent")
    val context = Context(testInvocationContext(agent = agent))

    context.updateState("key", "value")

    assertEquals(agent, context.agent)
    assertEquals("value", context.state["key"])
    assertNull(context.functionCallId)
  }

  @Test
  fun updateState_writesThroughTheSharedActionsObject() {
    val actions = EventActions()
    val context = Context(testInvocationContext(), actions)

    context.updateState("key", "value")

    assertSame(actions, context.actions)
    assertSame(actions, context.eventActions)
    assertEquals("value", actions.stateDelta["key"])
    assertEquals("value", context.eventActions.stateDelta["key"])
  }

  @Test
  fun mergeEventActions_replacesTheObjectAndKeepsEveryWrite() {
    val original = EventActions()
    val context = Context(testInvocationContext(), original)

    context.updateState("before", "1")
    context.mergeEventActions(EventActions(stateDelta = mutableMapOf("merged" to "2")))
    context.updateState("after", "3")

    // The merge swaps the object, so a reference taken earlier stops receiving writes.
    assertNotSame(original, context.actions)
    assertNull(original.stateDelta["after"])
    // No write is lost across the swap, and the context still reports all three.
    assertEquals("1", context.actions.stateDelta["before"])
    assertEquals("2", context.actions.stateDelta["merged"])
    assertEquals("3", context.actions.stateDelta["after"])
    assertEquals("3", context.state["after"])
  }

  @Test
  fun context_isTheReadonlyViewOfItself() {
    val context = Context(testInvocationContext())

    assertSame(context, context.context)
  }

  @Test
  fun toolFlavor_alsoCarriesTheCallbackWrites() {
    val context = Context(testInvocationContext(), functionCallId = "fc-1", eventId = "evt-1")

    context.updateState("key", "value")

    assertEquals("fc-1", context.functionCallId)
    assertEquals("evt-1", context.eventId)
    assertEquals("value", context.state["key"])
    assertEquals("value", context.actions.stateDelta["key"])
  }

  @Test
  fun callbackContext_keepsItsOriginalConstructorParameterNames() {
    // Named arguments, so this stops compiling if the parameter is ever renamed. Keeping the old
    // names is what lets existing callers survive the merge; ToolContext's five are covered by
    // ToolContextTest.
    val actions = EventActions()

    val context =
      CallbackContext(invocationContext = testInvocationContext(), eventActions = actions)

    assertSame(actions, context.actions)
  }

  @Test
  fun bothSubclasses_areContexts_andShareItsBehavior() {
    val callbackContext: Context = CallbackContext(testInvocationContext())
    val toolContext: Context = ToolContext(testInvocationContext(), functionCallId = "fc-1")

    callbackContext.updateState("from-callback", "1")
    toolContext.updateState("from-tool", "2")

    // Both names reach the same implementation, which is the point of the merge.
    assertEquals("1", callbackContext.state["from-callback"])
    assertEquals("2", toolContext.state["from-tool"])
    assertSame(callbackContext, callbackContext.context)
    assertSame(toolContext, toolContext.context)
    // A tool context answers the callback surface too, and vice versa.
    assertNull(callbackContext.functionCallId)
    assertEquals("fc-1", toolContext.functionCallId)
  }
}
