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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolFilterTest {

  private val readTool = DummyTool(name = "read_file")
  private val writeTool = DummyTool(name = "write_file")

  @Test
  fun isToolSelected_nullFilter_selectsEveryTool() {
    val filter: ToolFilter? = null

    assertTrue(filter.isToolSelected(readTool))
    assertTrue(filter.isToolSelected(writeTool))
  }

  @Test
  fun isToolSelected_allowList_selectsOnlyListedNames() {
    val filter = ToolFilter.AllowList(setOf("read_file"))

    assertTrue(filter.isToolSelected(readTool))
    assertFalse(filter.isToolSelected(writeTool))
  }

  @Test
  fun isToolSelected_emptyAllowList_selectsNoTools() {
    val filter = ToolFilter.AllowList(emptySet())

    assertFalse(filter.isToolSelected(readTool))
  }

  @Test
  fun allowList_companion_buildsAllowListFromNames() {
    assertEquals(
      ToolFilter.AllowList(setOf("read_file", "write_file")),
      ToolFilter.allowList("read_file", "write_file"),
    )
  }

  @Test
  fun isToolSelected_predicate_delegatesToPredicate() {
    val filter = ToolFilter.Predicate { tool, _ -> tool.name.startsWith("read") }

    assertTrue(filter.isToolSelected(readTool))
    assertFalse(filter.isToolSelected(writeTool))
  }

  @Test
  fun isToolSelected_predicate_forwardsReadonlyContext() {
    val context = testInvocationContext(invocationId = "inv-1").toReadonlyContext()
    var received: ReadonlyContext? = null
    val filter = ToolFilter.Predicate { _, ctx ->
      received = ctx
      true
    }

    assertTrue(filter.isToolSelected(readTool, context))
    assertSame(context, received)
  }

  @Test
  fun isToolSelected_predicate_defaultsToNullReadonlyContext() {
    var called = false
    var received: ReadonlyContext? = null
    val filter = ToolFilter.Predicate { _, ctx ->
      called = true
      received = ctx
      true
    }

    assertTrue(filter.isToolSelected(readTool))
    assertTrue(called)
    assertNull(received)
  }
}
