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
@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.examples.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/** Runs [MessageWorkflow] and its Java port, [MessageWorkflowJava], with the same assertions. */
@RunWith(Parameterized::class)
class MessageWorkflowTest(private val port: Port) {

  private fun create(): Workflow =
    when (port) {
      Port.KOTLIN -> MessageWorkflow.create()
      Port.JAVA -> MessageWorkflowJava.create()
    }

  @Test
  fun run_sendsEachMessageInOrderAndStreamsTheSentenceInChunks(): Unit = runBlocking {
    // Act: the nodes pause between messages, so a run takes about 11 s.
    val run = runWorkflow(create(), "go")

    // Assert
    assertThat(run.failure).isNull()
    val messages = run.events.filter { !it.partial && it.content != null }
    val sentence =
      """
      This is a streaming message sent in chunks.

      You can stream in markdown as well. For example, the table below:

      | Header 1 | Header 2 |
      |----------|----------|
      | Cell 1   | Cell 2   |
      | Cell 3   | Cell 4   |
      """
        .trimIndent()
    assertThat(messages.map { it.nodeName to it.contentText() })
      .containsExactly(
        "send_string" to "#1 This is a simple string message.",
        "send_multimodal" to "#2 Here is a multi-modal message with an inline image (red square):",
        "multiple_messages" to "#3 Multiple messages",
        "multiple_messages" to "Processing step 1...",
        "multiple_messages" to "Processing step 2...",
        "multiple_messages" to "Done processing.",
        "stream_sentence" to "#4 Starting to stream...",
        "stream_sentence" to sentence,
      )
      .inOrder()
    assertThat(run.outputs).isEmpty()
    val multimodal = messages.single { it.nodeName == "send_multimodal" }
    val image = assertNotNull(multimodal.content).parts.mapNotNull { it.inlineData }.single()
    val imageData = assertNotNull(image.data)
    assertThat(image.mimeType).isEqualTo("image/png")
    assertThat(imageData).hasLength(149)
    assertThat(imageData.copyOf(4)).isEqualTo(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    // The chunks arrive between the announcement and the whole sentence.
    val chunks = run.events.filter { it.partial }
    val announcement = messages.single { it.contentText() == "#4 Starting to stream..." }
    val firstChunkIndex = run.events.indexOf(announcement) + 1
    assertThat(run.events.subList(firstChunkIndex, firstChunkIndex + chunks.size))
      .containsExactlyElementsIn(chunks)
      .inOrder()
    assertThat(run.events[firstChunkIndex + chunks.size]).isSameInstanceAs(messages.last())
    assertThat(chunks.map { it.contentText() }).isEqualTo(sentence.chunked(5))
    // The sample waits 200 ms per chunk; half that still fails a port that sends them at once.
    val chunkArrivals = run.arrivals.filterIndexed { i, _ -> run.events[i].partial }
    assertThat(chunkArrivals.last() - chunkArrivals.first())
      .isAtLeast(100.milliseconds * (chunks.size - 1))
  }

  companion object {
    @JvmStatic @Parameters(name = "{0}") fun ports() = Port.entries
  }
}
