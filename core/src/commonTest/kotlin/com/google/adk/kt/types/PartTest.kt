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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PartTest {
  @Test
  fun equals_sameData_returnsTrue() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))

    assertEquals(part1, part2)
  }

  @Test
  fun equals_differentData_returnsFalse() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 4))

    assertNotEquals(part1, part2)
  }

  @Test
  fun equals_oneDataNull_returnsFalse() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = null)

    assertNotEquals(part1, part2)
  }

  @Test
  fun equals_bothDataNull_returnsTrue() {
    val part1 = Part(thought = true, thoughtSignature = null)
    val part2 = Part(thought = true, thoughtSignature = null)

    assertEquals(part1, part2)
  }

  @Test
  fun hashCode_sameData_returnsSameHashCode() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))

    assertEquals(part1.hashCode(), part2.hashCode())
  }

  // Part is hand-written rather than a data class, so a new field has to be added to equals,
  // hashCode and copy by hand or it silently drops out of all three.
  @Test
  fun equals_differentToolCall_returnsFalse() {
    val part1 = Part(toolCall = ToolCall(id = "tc1"))
    val part2 = Part(toolCall = ToolCall(id = "tc2"))

    assertNotEquals(part1, part2)
    assertNotEquals(part1.hashCode(), part2.hashCode())
  }

  @Test
  fun equals_differentToolResponse_returnsFalse() {
    val part1 = Part(toolResponse = ToolResponse(id = "tc1"))
    val part2 = Part(toolResponse = ToolResponse(id = "tc2"))

    assertNotEquals(part1, part2)
    assertNotEquals(part1.hashCode(), part2.hashCode())
  }

  @Test
  fun copy_preservesServerSideToolParts() {
    val part =
      Part(text = "hi", toolCall = ToolCall(id = "tc1"), toolResponse = ToolResponse(id = "tc1"))

    assertEquals(part.toolCall, part.copy(text = "bye").toolCall)
    assertEquals(part.toolResponse, part.copy(text = "bye").toolResponse)
  }

  @Test
  fun equals_differentExecutableCode_returnsFalse() {
    val part1 = Part(executableCode = ExecutableCode(code = "print(1)"))
    val part2 = Part(executableCode = ExecutableCode(code = "print(2)"))

    assertNotEquals(part1, part2)
    assertNotEquals(part1.hashCode(), part2.hashCode())
  }

  @Test
  fun equals_differentCodeExecutionResult_returnsFalse() {
    val part1 = Part(codeExecutionResult = CodeExecutionResult(output = "1"))
    val part2 = Part(codeExecutionResult = CodeExecutionResult(output = "2"))

    assertNotEquals(part1, part2)
    assertNotEquals(part1.hashCode(), part2.hashCode())
  }

  @Test
  fun equals_differentMediaResolution_returnsFalse() {
    val part1 = Part(mediaResolution = PartMediaResolution(numTokens = 64))
    val part2 = Part(mediaResolution = PartMediaResolution(numTokens = 256))

    assertNotEquals(part1, part2)
    assertNotEquals(part1.hashCode(), part2.hashCode())
  }

  @Test
  fun copy_preservesCodeExecutionAndMediaResolutionParts() {
    val part =
      Part(
        text = "hi",
        executableCode = ExecutableCode(code = "print(1)", language = Language.PYTHON),
        codeExecutionResult = CodeExecutionResult(outcome = Outcome.OUTCOME_OK, output = "ok"),
        mediaResolution =
          PartMediaResolution(
            level = PartMediaResolutionLevel.MEDIA_RESOLUTION_HIGH,
            numTokens = 256,
          ),
      )

    assertEquals(part.executableCode, part.copy(text = "bye").executableCode)
    assertEquals(part.codeExecutionResult, part.copy(text = "bye").codeExecutionResult)
    assertEquals(part.mediaResolution, part.copy(text = "bye").mediaResolution)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val part =
      Part(
        text = "hi",
        inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1)),
        fileData = FileData(fileUri = "gs://bucket/file"),
        functionCall = FunctionCall(name = "fn"),
        functionResponse = FunctionResponse(name = "fn"),
        thought = true,
        thoughtSignature = byteArrayOf(1, 2, 3),
        videoMetadata = VideoMetadata(fps = 1.0),
        toolCall = ToolCall(id = "tc1"),
        toolResponse = ToolResponse(id = "tc1"),
        partMetadata = mapOf("key" to "value"),
        executableCode = ExecutableCode(code = "print(1)"),
        codeExecutionResult = CodeExecutionResult(output = "1"),
        mediaResolution = PartMediaResolution(numTokens = 64),
      )

    assertEquals(part.copy(), part.toBuilder().build())
    assertEquals(part.copy(text = "bye"), part.toBuilder().text("bye").build())
  }
}
