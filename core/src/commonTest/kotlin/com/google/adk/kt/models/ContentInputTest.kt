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
package com.google.adk.kt.models

import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the four construction rules on [ContentInput].
 *
 * Each rejecting case trips exactly one rule; the accepting cases trip none.
 */
class ContentInputTest {

  @Test
  fun constructor_noParts_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      ContentInput(Content(role = Role.USER, parts = listOf()))
    }
  }

  @Test
  fun constructor_functionCallPart_throwsIllegalArgumentException() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        ContentInput(userMessage(Part(functionCall = FunctionCall(name = "doIt"))))
      }

    assertContains(failure.message.orEmpty(), "User message cannot contain function calls.")
  }

  @Test
  fun constructor_functionResponseMixedWithText_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      ContentInput(
        userMessage(
          Part(functionResponse = FunctionResponse(name = "doIt")),
          Part(text = "and some prose"),
        )
      )
    }
  }

  @Test
  fun constructor_partialFunctionResponses_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      ContentInput(userFunctionResponse("doIt", id = null), partial = true)
    }
  }

  @Test
  fun constructor_functionResponsesOnly_isAccepted() {
    val input =
      ContentInput(
        userMessage(
          Part(functionResponse = FunctionResponse(name = "first")),
          Part(functionResponse = FunctionResponse(name = "second")),
        )
      )

    assertEquals(2, input.content.parts.size)
  }

  @Test
  fun constructor_partialUserText_isAccepted() {
    val input = ContentInput(userMessage("half a thought"), partial = true)

    assertEquals(true, input.partial)
  }

  @Test
  fun constructor_modelRole_isAccepted() {
    // Any role is allowed: no other ADK port checks it, so neither does this one.
    val input = ContentInput(modelMessage("from the model"))

    assertEquals(Role.MODEL, input.content.role)
  }
}
