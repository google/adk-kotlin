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
package com.google.adk.kt.a2a.converters

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import io.a2a.client.MessageEvent
import io.a2a.client.TaskEvent
import io.a2a.client.TaskUpdateEvent
import io.a2a.spec.Artifact
import io.a2a.spec.DataPart
import io.a2a.spec.FilePart
import io.a2a.spec.FileWithBytes
import io.a2a.spec.FileWithUri
import io.a2a.spec.Message
import io.a2a.spec.Part as A2APart
import io.a2a.spec.Task
import io.a2a.spec.TaskArtifactUpdateEvent
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import java.util.Base64
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LegacyA2aConvertersTest {

  private val testAgent = DummyAgent(name = "test_agent")

  private val invocationContext =
    InvocationContext(
      invocationId = "invocation-1",
      agent = testAgent,
      branch = "main",
      session = testSession(),
      runConfig = null,
    )

  @Test
  fun toAdk_withTextPart_returnsAdkTextPart() {
    val textPart = TextPart("Hello")
    val result = textPart.toAdk()
    assertThat(result.text).isEqualTo("Hello")
  }

  @Test
  fun toA2A_withTextPart_returnsTextPart() {
    val part = Part(text = "Hello")
    val result = part.toLegacyA2aPart()
    assertThat((result as TextPart).text).isEqualTo("Hello")
  }

  @Test
  fun toAdk_withFilePartUri_returnsAdkFilePart() {
    val filePart = FilePart(FileWithUri("text/plain", "file.txt", "http://file.txt"))
    val result = filePart.toAdk()
    val fileData = result.fileData
    assertThat(fileData).isNotNull()
    assertThat(fileData!!.mimeType).isEqualTo("text/plain")
    assertThat(fileData.fileUri).isEqualTo("http://file.txt")
  }

  @Test
  fun toA2A_withFileDataPart_returnsFilePartWithUri() {
    val part = Part(fileData = FileData(mimeType = "text/plain", fileUri = "http://file.txt"))
    val result = part.toLegacyA2aPart()
    assertThat((result as FilePart).file.mimeType()).isEqualTo("text/plain")
    assertThat((result.file as FileWithUri).uri()).isEqualTo("http://file.txt")
  }

  @Test
  fun toAdk_withFilePartBytes_returnsAdkBlobPart() {
    val bytes = "file content".toByteArray()
    val encoded = Base64.getEncoder().encodeToString(bytes)
    val filePart = FilePart(FileWithBytes("text/plain", "file.txt", encoded))
    val result = filePart.toAdk()
    val blob = result.inlineData
    assertThat(blob).isNotNull()
    assertThat(blob!!.mimeType).isEqualTo("text/plain")
    assertThat(blob.displayName).isEqualTo("file.txt")
    assertThat(blob.data).isNotNull()
    assertThat(String(blob.data!!)).isEqualTo("file content")
  }

  @Test
  fun toA2A_withInlineDataPart_returnsFilePartWithBytes() {
    val bytes = "content".toByteArray()
    val part =
      Part(inlineData = Blob(mimeType = "text/plain", displayName = "file.txt", data = bytes))
    val result = part.toLegacyA2aPart()
    assertThat((result as FilePart).file.mimeType()).isEqualTo("text/plain")
    assertThat(result.file.name()).isEqualTo("file.txt")
    assertThat((result.file as FileWithBytes).bytes())
      .isEqualTo(Base64.getEncoder().encodeToString(bytes))
  }

  @Test
  fun toAdk_withDataPartFunctionCall_returnsAdkFunctionCallPart() {
    val data = mapOf("name" to "func", "id" to "1", "args" to mapOf<String, Any>())
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionCall = result.functionCall
    assertThat(functionCall).isNotNull()
    assertThat(functionCall!!.name).isEqualTo("func")
    assertThat(functionCall.id).isEqualTo("1")
    assertThat(functionCall.args).isEqualTo(mapOf<String, Any>())
  }

  @Test
  fun toA2A_withFunctionCallPart_returnsDataPart() {
    val part = Part(functionCall = FunctionCall(name = "func", id = "1", args = mapOf()))
    val result = part.toLegacyA2aPart()
    val dataPart = result as DataPart
    assertThat(dataPart.data["name"]).isEqualTo("func")
    assertThat(dataPart.data["id"]).isEqualTo("1")
    assertThat(dataPart.metadata[MetadataKeys.TYPE]).isEqualTo(TYPE_FUNCTION_CALL)
  }

  @Test
  fun toAdk_withDataPartFunctionResponse_returnsAdkFunctionResponsePart() {
    val data = mapOf("name" to "func", "id" to "1", "response" to mapOf<String, Any>())
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_RESPONSE)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionResponse = result.functionResponse
    assertThat(functionResponse).isNotNull()
    assertThat(functionResponse!!.name).isEqualTo("func")
    assertThat(functionResponse.id).isEqualTo("1")
    assertThat(functionResponse.response).isEqualTo(mapOf<String, Any>())
  }

  @Test
  fun toA2A_withFunctionResponsePart_returnsDataPart() {
    val part =
      Part(functionResponse = FunctionResponse(name = "func", id = "1", response = mapOf()))
    val result = part.toLegacyA2aPart()
    val dataPart = result as DataPart
    assertThat(dataPart.data["name"]).isEqualTo("func")
    assertThat(dataPart.data["id"]).isEqualTo("1")
    assertThat(dataPart.metadata[MetadataKeys.TYPE]).isEqualTo(TYPE_FUNCTION_RESPONSE)
  }

  @Test
  fun toAdk_convertsAllSupportedParts() {
    val a2aParts =
      listOf(TextPart("text"), FilePart(FileWithUri("text/plain", "file.txt", "http://file.txt")))
    val result = a2aParts.toAdk()
    assertThat(result.size).isEqualTo(2)
    assertThat(result[0].text).isEqualTo("text")
    assertThat(result[1].fileData).isNotNull()
  }

  @Test
  fun toAdk_withDataPartWithEmptyStringCoercedToEmptyMap() {
    val data = mapOf("name" to "func", "id" to "1", "args" to "")
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionCall = result.functionCall
    assertThat(functionCall).isNotNull()
    assertThat(functionCall!!.args).isEqualTo(mapOf<String, Any>())
  }

  @Test
  fun toAdk_withDataPartWithNonMapCoercedToMap() {
    val data = mapOf("name" to "func", "id" to "1", "args" to 123)
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionCall = result.functionCall
    assertThat(functionCall).isNotNull()
    assertThat(functionCall!!.args).isEqualTo(mapOf("value" to 123))
  }

  @Test
  fun toAdk_withDataPartWithJsonStringCoercedToMap() {
    val data = mapOf("name" to "func", "id" to "1", "args" to "{\"key\": \"value\"}")
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionCall = result.functionCall
    assertThat(functionCall).isNotNull()
    assertThat(functionCall!!.args).isEqualTo(mapOf("key" to "value"))
  }

  @Test
  fun toAdk_withDataPartWithInvalidJsonStringCoercedToMap() {
    val data = mapOf("name" to "func", "id" to "1", "args" to "{invalid}")
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionCall = result.functionCall
    assertThat(functionCall).isNotNull()
    assertThat(functionCall!!.args).isEqualTo(mapOf("value" to "{invalid}"))
  }

  @Test
  fun toAdk_withFilePartBytes_handlesInvalidBase64() {
    val filePart = FilePart(FileWithBytes("text/plain", "file.txt", "invalid-base64!"))
    assertFailsWith<IllegalArgumentException> { filePart.toAdk() }
  }

  @Test
  fun toAdk_withFilePartBytes_handlesNullBytes_throwsException() {
    val filePart = FilePart(FileWithBytes("text/plain", "file.txt", null))
    assertFailsWith<NullPointerException> { filePart.toAdk() }
  }

  @Test
  fun clientEventToEvent_withMessageEvent_returnsEvent() {
    val a2aMessage =
      Message.Builder()
        .messageId("msg-1")
        .role(Message.Role.USER)
        .parts(listOf(TextPart("Hello")))
        .build()
    val messageEvent = MessageEvent(a2aMessage)

    val result = messageEvent.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.author).isEqualTo("test_agent")
    assertThat(result.content?.parts?.get(0)?.text).isEqualTo("Hello")
  }

  @Test
  fun messageToEvent_convertsMessage() {
    val a2aMessage =
      Message.Builder()
        .messageId("msg-1")
        .role(Message.Role.USER)
        .parts(listOf(TextPart("test-message")))
        .build()

    val result = a2aMessage.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.author).isEqualTo("test_agent")
    assertThat(result.content?.role).isEqualTo("model")
    assertThat(result.content?.parts?.get(0)?.text).isEqualTo("test-message")
  }

  @Test
  fun taskToEvent_withArtifacts_returnsEventFromLastArtifact() {
    val a2aPart = TextPart("Artifact content")
    val artifact = Artifact.Builder().artifactId("artifact-1").parts(listOf(a2aPart)).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.COMPLETED))
        .artifacts(listOf(artifact))
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.content?.parts?.get(0)?.text).isEqualTo("Artifact content")
  }

  @Test
  fun taskToEvent_withStatusMessage_returnsEvent() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("Status message"))).build()
    val status = TaskStatus(TaskState.WORKING, statusMessage, null)
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .artifacts(emptyList())
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.content?.parts?.get(0)?.text).isEqualTo("Status message")
  }

  @Test
  fun taskToEvent_withFailedState_setsErrorCode() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("Task failed"))).build()
    val status = TaskStatus(TaskState.FAILED, statusMessage, null)
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .artifacts(emptyList())
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.errorMessage).isEqualTo("Task failed")
  }

  @Test
  fun taskToEvent_withInputRequired_parsesLongRunningToolIds() {
    val data = mapOf("name" to "myTool", "id" to "call_123", "args" to mapOf<String, Any>())
    val metadata =
      mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL, MetadataKeys.IS_LONG_RUNNING to true)
    val dataPart = DataPart(data, metadata)

    val statusData =
      mapOf("name" to "messageTools", "id" to "msg_123", "args" to mapOf<String, Any>())
    val statusMetadata =
      mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL, MetadataKeys.IS_LONG_RUNNING to true)
    val statusDataPart = DataPart(statusData, statusMetadata)

    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(statusDataPart)).build()
    val status = TaskStatus(TaskState.INPUT_REQUIRED, statusMessage, null)

    val artifact = Artifact.Builder().artifactId("artifact-1").parts(listOf(dataPart)).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .artifacts(listOf(artifact))
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.longRunningToolIds).isEqualTo(setOf("call_123", "msg_123"))
  }

  @Test
  fun taskToEvent_withGroundingMetadata_returnsEvent() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("Status message"))).build()
    val status = TaskStatus(TaskState.WORKING, statusMessage, null)

    val groundingMetadataJson = "{\"imageSearchQueries\":[\"test-query\"]}"
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .metadata(mapOf(MetadataKeys.GROUNDING to groundingMetadataJson))
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.groundingMetadata).isNotNull()
  }

  @Test
  fun taskToEvent_withCustomMetadata_returnsEvent() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("Status message"))).build()
    val status = TaskStatus(TaskState.WORKING, statusMessage, null)

    val customMetadataMap = mapOf("test-key" to "test-value")
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .metadata(mapOf(MetadataKeys.CUSTOM to customMetadataMap))
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.customMetadata?.get(ADK_METADATA_TASK_ID)).isEqualTo("task-1")
    assertThat(result.customMetadata?.get(ADK_METADATA_CONTEXT_ID)).isEqualTo("context-1")
    assertThat(result.customMetadata?.get("test-key")).isEqualTo("test-value")
  }

  @Test
  fun taskToEvent_withErrorCode_returnsEvent() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("Status message"))).build()
    val status = TaskStatus(TaskState.WORKING, statusMessage, null)

    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .metadata(mapOf(MetadataKeys.ERROR_CODE to "STOP"))
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.errorCode).isEqualTo("STOP")
  }

  @Test
  fun clientEventToEvent_withTaskUpdateEventAndThought_returnsThoughtEvent() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("thought-1"))).build()
    val status = TaskStatus(TaskState.WORKING, statusMessage, null)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()

    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", false, null)
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.content?.parts?.get(0)?.text).isEqualTo("thought-1")
    assertThat(result.content?.parts?.get(0)?.thought).isEqualTo(true)
  }

  @Test
  fun clientEventToEvent_withTaskArtifactUpdateEvent_withLastChunkTrue_returnsTaskEvent() {
    val a2aPart = TextPart("Artifact content")
    val artifact = Artifact.Builder().artifactId("artifact-1").parts(listOf(a2aPart)).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.COMPLETED))
        .artifacts(listOf(artifact))
        .build()

    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .lastChunk(true)
        .contextId("context-1")
        .artifact(artifact)
        .taskId("task-id-1")
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.content?.parts?.get(0)?.text).isEqualTo("Artifact content")
  }

  @Test
  fun clientEventToEvent_withTaskArtifactUpdateEvent_withLastChunkFalse_returnsHandlingPartialEvent() {
    val a2aPart = TextPart("Artifact content")
    val artifact = Artifact.Builder().artifactId("artifact-1").parts(listOf(a2aPart)).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.COMPLETED))
        .artifacts(listOf(artifact))
        .build()

    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .lastChunk(false)
        .append(false)
        .contextId("context-1")
        .artifact(artifact)
        .taskId("task-id-1")
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.partial).isEqualTo(true)
  }

  @Test
  fun clientEventToEvent_withFinalTaskStatusUpdateEvent_withMessage_returnsEvent() {
    val statusMessage =
      Message.Builder()
        .role(Message.Role.AGENT)
        .parts(listOf(TextPart("Final status message")))
        .build()
    val status = TaskStatus(TaskState.COMPLETED, statusMessage, null)
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", true, null)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.content?.parts?.get(0)?.text).isEqualTo("Final status message")
    assertThat(result.partial).isEqualTo(false)
    assertThat(result.turnComplete).isEqualTo(true)
  }

  @Test
  fun clientEventToEvent_withFailedTaskStatusUpdateEvent_returnsErrorEvent() {
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(TextPart("Task failed"))).build()
    val status = TaskStatus(TaskState.FAILED, statusMessage, null)
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", true, null)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.errorMessage).isEqualTo("Task failed")
    assertThat(result.turnComplete).isEqualTo(true)
  }

  @Test
  fun toLegacyA2aParts_validContent_returnsParts() {
    val textPart = Part(text = "hello")
    val content = Content(parts = listOf(textPart))
    val list = content.toLegacyA2aParts(false)
    assertThat(list.size).isEqualTo(1)
    assertThat((list[0] as TextPart).text).isEqualTo("hello")
  }

  @Test
  fun toLegacyA2aMessage_withUserAuthor_returnsUserRole() {
    val event = Event(author = "user", content = userMessage("hello"))
    val result = event.toLegacyA2aMessage()
    assertThat(result.role).isEqualTo(Message.Role.USER)
  }

  @Test
  fun toLegacyA2aMessage_withAgentAuthor_returnsAgentRole() {
    val event = Event(author = "agent", content = modelMessage("hello"))
    val result = event.toLegacyA2aMessage()
    assertThat(result.role).isEqualTo(Message.Role.AGENT)
  }

  @Test
  fun toLegacyA2aMessage_addsAuthorToMetadata() {
    val event = Event(author = "test_author", content = userMessage("hello"))
    val result = event.toLegacyA2aMessage()
    assertThat(result.metadata?.get(MetadataKeys.AUTHOR)).isEqualTo("test_author")
  }

  @Test
  fun extractLegacyA2aParts_sessionHasEvents_returnsFormattedParts() {
    val userEvent = Event(author = Role.USER, content = userMessage("hello"))
    val agentEvent = Event(author = "test_agent", content = modelMessage("hi"))
    val otherAgentEvent = Event(author = "other_agent", content = modelMessage("hey"))

    val session =
      Session(
        key = SessionKey(appName = "demo", userId = "user", id = "session-1"),
        events = mutableListOf(userEvent, agentEvent, otherAgentEvent),
      )

    val mockAgent = DummyAgent(name = "test_agent")
    val ctx = InvocationContext(agent = mockAgent, session = session, runConfig = null)

    val parts = ctx.extractLegacyA2aParts()
    assertThat(parts.size).isEqualTo(2)
    assertThat((parts[0] as TextPart).text).isEqualTo("For context:")
    assertThat((parts[1] as TextPart).text).isEqualTo("[other_agent] said: hey")
  }

  @Test
  fun shouldBuffer_withTaskUpdateEventNonStatus_returnsTrue() {
    val artifact =
      Artifact.Builder().artifactId("artifact-1").parts(listOf(TextPart("content"))).build()
    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .artifact(artifact)
        .taskId("task-1")
        .contextId("context-1")
        .build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.shouldBuffer()).isTrue()
  }

  @Test
  fun shouldBuffer_withTaskUpdateEventStatus_returnsFalse() {
    val status = TaskStatus(TaskState.WORKING)
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", false, null)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.shouldBuffer()).isFalse()
  }

  @Test
  fun shouldBuffer_withTaskEventWithArtifacts_returnsTrue() {
    val artifact =
      Artifact.Builder().artifactId("artifact-1").parts(listOf(TextPart("content"))).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .artifacts(listOf(artifact))
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskEvent(task)

    assertThat(event.shouldBuffer()).isTrue()
  }

  @Test
  fun shouldBuffer_withTaskEventWithoutArtifacts_returnsFalse() {
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .artifacts(emptyList())
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskEvent(task)

    assertThat(event.shouldBuffer()).isFalse()
  }

  @Test
  fun shouldBuffer_withOtherEvent_returnsTrue() {
    val message =
      Message.Builder()
        .messageId("msg-1")
        .role(Message.Role.USER)
        .parts(listOf(TextPart("hello")))
        .build()
    val event = MessageEvent(message)

    assertThat(event.shouldBuffer()).isTrue()
  }

  @Test
  fun shouldResetBuffer_withTaskUpdateEventArtifactNotAppendNotLast_returnsTrue() {
    val artifact =
      Artifact.Builder().artifactId("artifact-1").parts(listOf(TextPart("content"))).build()
    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .artifact(artifact)
        .append(false)
        .lastChunk(false)
        .taskId("task-1")
        .contextId("context-1")
        .build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.shouldResetBuffer()).isTrue()
  }

  @Test
  fun shouldResetBuffer_withTaskUpdateEventArtifactAppend_returnsFalse() {
    val artifact =
      Artifact.Builder().artifactId("artifact-1").parts(listOf(TextPart("content"))).build()
    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .artifact(artifact)
        .append(true)
        .lastChunk(false)
        .taskId("task-1")
        .contextId("context-1")
        .build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.shouldResetBuffer()).isFalse()
  }

  @Test
  fun shouldResetBuffer_withTaskUpdateEventArtifactLast_returnsFalse() {
    val artifact =
      Artifact.Builder().artifactId("artifact-1").parts(listOf(TextPart("content"))).build()
    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .artifact(artifact)
        .append(false)
        .lastChunk(true)
        .taskId("task-1")
        .contextId("context-1")
        .build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.shouldResetBuffer()).isFalse()
  }

  @Test
  fun shouldResetBuffer_withTaskUpdateEventNonArtifact_returnsFalse() {
    val status = TaskStatus(TaskState.WORKING)
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", false, null)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.shouldResetBuffer()).isFalse()
  }

  @Test
  fun shouldResetBuffer_withNonTaskUpdateEvent_returnsFalse() {
    val message =
      Message.Builder()
        .messageId("msg-1")
        .role(Message.Role.USER)
        .parts(listOf(TextPart("hello")))
        .build()
    val event = MessageEvent(message)

    assertThat(event.shouldResetBuffer()).isFalse()
  }

  @Test
  fun shouldResetBuffer_withTaskEvent_returnsTrue() {
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.WORKING))
        .build()
    val event = TaskEvent(task)

    assertThat(event.shouldResetBuffer()).isTrue()
  }

  @Test
  fun isCompleted_withTaskEventCompleted_returnsTrue() {
    val status = TaskStatus(TaskState.COMPLETED)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskEvent(task)

    assertThat(event.isCompleted()).isTrue()
  }

  @Test
  fun isCompleted_withTaskEventNotCompleted_returnsFalse() {
    val status = TaskStatus(TaskState.WORKING)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskEvent(task)

    assertThat(event.isCompleted()).isFalse()
  }

  @Test
  fun isCompleted_withTaskUpdateEventCompleted_returnsTrue() {
    val status = TaskStatus(TaskState.COMPLETED)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", true, null)
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.isCompleted()).isTrue()
  }

  @Test
  fun isCompleted_withTaskUpdateEventNotCompleted_returnsFalse() {
    val status = TaskStatus(TaskState.WORKING)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", false, null)
    val event = TaskUpdateEvent(task, updateEvent)

    assertThat(event.isCompleted()).isFalse()
  }

  @Test
  fun isCompleted_withOtherEvent_returnsFalse() {
    val message =
      Message.Builder()
        .messageId("msg-1")
        .role(Message.Role.USER)
        .parts(listOf(TextPart("hello")))
        .build()
    val event = MessageEvent(message)

    assertThat(event.isCompleted()).isFalse()
  }

  @Test
  fun clientEventToEvent_withTaskArtifactUpdateEvent_withLastChunkAndPartial_returnsNull() {
    val a2aPart = TextPart("Artifact content")
    val artifact = Artifact.Builder().artifactId("artifact-1").parts(listOf(a2aPart)).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.COMPLETED))
        .artifacts(listOf(artifact))
        .build()

    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .lastChunk(true)
        .metadata(mapOf(MetadataKeys.PARTIAL to true))
        .contextId("context-1")
        .artifact(artifact)
        .taskId("task-id-1")
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNull()
  }

  @Test
  fun clientEventToEvent_withTaskArtifactUpdateEvent_withEmptyParts_returnsNull() {
    val partsList = mutableListOf<A2APart<*>>(TextPart("dummy"))
    val artifact = Artifact.Builder().artifactId("artifact-1").parts(partsList).build()
    partsList.clear()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(TaskStatus(TaskState.COMPLETED))
        .artifacts(listOf(artifact))
        .build()

    val updateEvent =
      TaskArtifactUpdateEvent.Builder()
        .lastChunk(true)
        .contextId("context-1")
        .artifact(artifact)
        .taskId("task-id-1")
        .build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNull()
  }

  @Test
  fun taskToEvent_withNonInputRequiredState_assertLongRunningToolIdsIsEmpty() {
    val data = mapOf("name" to "myTool", "id" to "call_123", "args" to mapOf<String, Any>())
    val metadata =
      mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL, MetadataKeys.IS_LONG_RUNNING to true)
    val dataPart = DataPart(data, metadata)

    val statusData =
      mapOf("name" to "messageTools", "id" to "msg_123", "args" to mapOf<String, Any>())
    val statusMetadata =
      mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_CALL, MetadataKeys.IS_LONG_RUNNING to true)
    val statusDataPart = DataPart(statusData, statusMetadata)

    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(statusDataPart)).build()
    val status = TaskStatus(TaskState.WORKING, statusMessage, null)

    val artifact = Artifact.Builder().artifactId("artifact-1").parts(listOf(dataPart)).build()
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .artifacts(listOf(artifact))
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.longRunningToolIds).isEmpty()
  }

  @Test
  fun clientEventToEvent_withFailedTaskStatusUpdateEvent_noTextPart_returnsFallbackErrorMessage() {
    val nonTextPart = FilePart(FileWithUri("text/plain", "file.txt", "http://file.txt"))
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(nonTextPart)).build()
    val status = TaskStatus(TaskState.FAILED, statusMessage, null)
    val updateEvent = TaskStatusUpdateEvent("task-1", status, "context-1", true, null)
    val task = Task.Builder().id("task-1").contextId("context-1").status(status).build()
    val event = TaskUpdateEvent(task, updateEvent)

    val result = event.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result!!.errorMessage).isEqualTo(DEFAULT_ERROR_MESSAGE)
    assertThat(result.turnComplete).isEqualTo(true)
  }

  @Test
  fun taskToEvent_withFailedState_noTextPart_returnsFallbackErrorMessage() {
    val nonTextPart = FilePart(FileWithUri("text/plain", "file.txt", "http://file.txt"))
    val statusMessage =
      Message.Builder().role(Message.Role.AGENT).parts(listOf(nonTextPart)).build()
    val status = TaskStatus(TaskState.FAILED, statusMessage, null)
    val task =
      Task.Builder()
        .id("task-1")
        .contextId("context-1")
        .status(status)
        .artifacts(emptyList())
        .build()

    val result = task.toAdkEvent(invocationContext)
    assertThat(result).isNotNull()
    assertThat(result.errorMessage).isEqualTo(DEFAULT_ERROR_MESSAGE)
  }

  @Test
  fun toAdk_withUnsupportedPartType_throwsException() {
    val unsupportedPart =
      object : A2APart<Any>() {
        override fun getKind(): A2APart.Kind = A2APart.Kind.TEXT

        override fun getMetadata(): Map<String, Any> = emptyMap()
      }
    val exception = assertFailsWith<IllegalArgumentException> { unsupportedPart.toAdk() }
    assertThat(exception.message).contains("Unsupported A2A Part type")
  }

  @Test
  fun toA2A_withUnsupportedAdkPart_throwsException() {
    val emptyPart = Part()
    val exception = assertFailsWith<IllegalArgumentException> { emptyPart.toLegacyA2aPart() }
    assertThat(exception.message).contains("Unsupported ADK Part content")
  }

  @Test
  fun toAdk_withDataPartFunctionResponseWithNonMapCoercedToMap() {
    val data = mapOf("name" to "func", "id" to "1", "response" to 456)
    val metadata = mapOf(MetadataKeys.TYPE to TYPE_FUNCTION_RESPONSE)
    val dataPart = DataPart(data, metadata)
    val result = dataPart.toAdk()
    val functionResponse = result.functionResponse
    assertThat(functionResponse).isNotNull()
    assertThat(functionResponse!!.response).isEqualTo(mapOf("value" to 456))
  }
}
