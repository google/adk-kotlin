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
package com.google.adk.kt.testing

import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the part-list overloads, `modelFunctionCall` and the nullable function-response id in
 * TestContent.kt.
 *
 * Each case compares the whole value, so a helper that builds the wrong shape fails rather than
 * matching on a field that happens to be null either way.
 */
class TestContentTest {

  private val blob = Part(inlineData = Blob(mimeType = "audio/pcm", data = byteArrayOf(1)))
  private val text = Part(text = "and some words")

  @Test
  fun userMessage_parts_keepsThemInOrderUnderUserRole() {
    assertEquals(Content(role = Role.USER, parts = listOf(blob, text)), userMessage(blob, text))
  }

  @Test
  fun modelMessage_parts_keepsThemInOrderUnderModelRole() {
    assertEquals(Content(role = Role.MODEL, parts = listOf(text, blob)), modelMessage(text, blob))
  }

  @Test
  fun modelFunctionCall_carriesNameArgsAndIdUnderModelRole() {
    assertEquals(
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(functionCall = FunctionCall(name = "doIt", args = mapOf("k" to null), id = "c1"))
          ),
      ),
      modelFunctionCall("doIt", mapOf("k" to null), id = "c1"),
    )
  }

  @Test
  fun modelFunctionCallResponse_wrapsTheSameContent() {
    // Hand-built with non-default args: comparing against modelFunctionCall would not catch a drop.
    assertEquals(
      LlmResponse(
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(
                  functionCall = FunctionCall(name = "doIt", args = mapOf("k" to null), id = "c1")
                )
              ),
          )
      ),
      modelFunctionCallResponse("doIt", mapOf("k" to null), id = "c1"),
    )
  }

  @Test
  fun userFunctionResponse_noId_leavesIdNull() {
    assertEquals(
      Content(
        role = Role.USER,
        parts = listOf(Part(functionResponse = FunctionResponse(name = "doIt"))),
      ),
      userFunctionResponse("doIt", id = null),
    )
  }

  @Test
  fun userFunctionResponse_id_isCarriedOnTheResponse() {
    assertEquals(
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              functionResponse =
                FunctionResponse(name = "doIt", response = mapOf("r" to 1), id = "c1")
            )
          ),
      ),
      userFunctionResponse("doIt", "c1", response = mapOf("r" to 1)),
    )
  }
}
