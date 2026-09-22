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

import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Outcome
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.UsageMetadata
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class EventCommonTest {

  @Test
  fun constructor_initializesFieldsCorrectly() {
    val functionCall = FunctionCall("function_name", mapOf("key" to "value"))
    val content = Content(role = null, parts = listOf(Part(functionCall = functionCall)))
    val eventActions =
      EventActions(
        skipSummarization = true,
        stateDelta = ConcurrentHashMap(mutableMapOf("key" to "value")),
        artifactDelta = ConcurrentHashMap(mutableMapOf("artifact_key" to 1)),
        transferToAgent = "agent_id",
        escalate = true,
      )
    val usageMetadata =
      UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 20, totalTokenCount = 30)

    val event =
      Event(
        id = "event_id",
        invocationId = "invocation_id",
        author = "agent",
        content = content,
        actions = eventActions,
        longRunningToolIds = setOf("tool_id"),
        partial = true,
        errorCode = "error_code",
        errorMessage = "error_message",
        finishReason = FinishReason.STOP,
        usageMetadata = usageMetadata,
        interrupted = true,
        timestamp = 123456789L,
      )

    assertThat(event.functionCalls().map { it.name to it.args })
      .containsExactly(functionCall.name to functionCall.args)
    assertThat(event.functionResponses()).isEmpty()
    assertThat(event.longRunningToolIds).containsExactly("tool_id")
    assertThat(event.partial).isTrue()
    assertThat(event.errorCode).isEqualTo("error_code")
    assertThat(event.errorMessage).isEqualTo("error_message")
    assertThat(event.finishReason).isEqualTo(FinishReason.STOP)
    assertThat(event.usageMetadata).isEqualTo(usageMetadata)
    assertThat(event.interrupted).isTrue()
    assertThat(event.timestamp).isEqualTo(123456789L)
    assertThat(event.actions).isEqualTo(eventActions)
  }

  @Test
  fun constructor_withMinimalArgs_initializesDefaults() {
    val event = Event(id = "event_id", invocationId = "invocation_id", author = "agent")
    assertThat(event.id).isEqualTo("event_id")
    assertThat(event.invocationId).isEqualTo("invocation_id")
    assertThat(event.author).isEqualTo("agent")
    assertThat(event.actions).isEqualTo(EventActions())
  }

  @Test
  fun constructor_defaultTimestamp_isSetToCurrentTime() {
    val before = System.currentTimeMillis()
    val event = Event(id = "event_id", invocationId = "invocation_id", author = "agent")
    val after = System.currentTimeMillis()
    assertThat(event.timestamp).isAtLeast(before)
    assertThat(event.timestamp).isAtMost(after)
  }

  @Test
  fun equals_withSameContent_returnsTrue() {
    val event1 =
      Event(
        id = "event_id",
        invocationId = "invocation_id",
        author = "agent",
        timestamp = 123456789L,
      )
    val event2 =
      Event(
        id = "event_id",
        invocationId = "invocation_id",
        author = "agent",
        timestamp = 123456789L,
      )

    assertThat(event1).isEqualTo(event2)
  }

  @Test
  fun hashCode_withSameContent_isEqual() {
    val event1 =
      Event(
        id = "event_id",
        invocationId = "invocation_id",
        author = "agent",
        timestamp = 123456789L,
      )
    val event2 =
      Event(
        id = "event_id",
        invocationId = "invocation_id",
        author = "agent",
        timestamp = 123456789L,
      )

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode())
  }

  @Test
  fun isFinalResponse_returnsTrueIfNoToolCalls() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts = listOf(Part(text = "hello", inlineData = null, fileData = null)),
          ),
      )
    assertThat(event.isFinalResponse).isTrue()
  }

  @Test
  fun isFinalResponse_returnsFalseIfToolCalls() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts =
              listOf(Part(functionCall = FunctionCall(name = "tool", args = mapOf("k" to "v")))),
          ),
      )
    assertThat(event.isFinalResponse).isFalse()
  }

  @Test
  fun isFinalResponse_returnsTrueIfSkipSummarization() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts =
              listOf(Part(functionCall = FunctionCall(name = "tool", args = mapOf("k" to "v")))),
          ),
        actions = EventActions(skipSummarization = true),
      )
    assertThat(event.isFinalResponse).isTrue()
  }

  @Test
  fun isFinalResponse_returnsTrueIfLongRunningToolIdsNotEmpty() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts =
              listOf(Part(functionCall = FunctionCall(name = "tool", args = mapOf("k" to "v")))),
          ),
        longRunningToolIds = setOf("tool_id"),
      )
    assertThat(event.isFinalResponse).isTrue()
  }

  @Test
  fun isFinalResponse_returnsFalseIfPartialIsTrue() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts = listOf(Part(text = "hello", inlineData = null, fileData = null)),
          ),
        partial = true,
      )
    assertThat(event.isFinalResponse).isFalse()
  }

  @Test
  fun isFinalResponse_returnsFalseIfTrailingCodeExecutionResult() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts =
              listOf(
                Part(text = "running code"),
                Part(codeExecutionResult = CodeExecutionResult(outcome = Outcome.OUTCOME_OK)),
              ),
          ),
      )
    assertThat(event.hasTrailingCodeExecutionResult()).isTrue()
    assertThat(event.isFinalResponse).isFalse()
  }

  @Test
  fun hasTrailingCodeExecutionResult_returnsFalseWhenNotLast() {
    val event =
      Event(
        id = "e1",
        invocationId = "i1",
        author = "agent",
        content =
          Content(
            role = null,
            parts =
              listOf(
                Part(codeExecutionResult = CodeExecutionResult(outcome = Outcome.OUTCOME_OK)),
                Part(text = "the answer is 4"),
              ),
          ),
      )
    assertThat(event.hasTrailingCodeExecutionResult()).isFalse()
    assertThat(event.isFinalResponse).isTrue()
  }
}
