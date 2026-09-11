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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ToolExecutionErrorTest {

  @Test
  fun construct_messageOnly_doesNotClassifyItself() {
    val failure = ToolExecutionError("the tool call did not complete")

    assertEquals("the tool call did not complete", failure.message)
    assertNull(failure.errorType)
  }

  @Test
  fun construct_toolErrorType_usesTheMemberNameAsTheWireForm() {
    for (type in ToolErrorType.entries) {
      assertEquals(type.name, ToolExecutionError("the tool call did not complete", type).errorType)
    }
  }

  @Test
  fun construct_unnamedCode_isCarriedThroughVerbatim() {
    assertEquals("418", ToolExecutionError("the tool call did not complete", "418").errorType)
  }

  @Test
  fun throw_isCaughtAsAnOrdinaryException() {
    val failure =
      assertFailsWith<Exception> { throw ToolExecutionError("the tool call did not complete") }

    assertEquals("the tool call did not complete", failure.message)
  }
}
