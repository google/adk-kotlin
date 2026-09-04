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
import com.google.adk.kt.serialization.LenientByteArraySerializer
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A part of a multi-modal prompt or response.
 *
 * A Part can contain one of the following:
 * - text: Plain text.
 * - inlineData: Binary data (e.g., image, audio).
 * - fileData: Data from a file.
 * - functionCall: A call to a function.
 * - functionResponse: The response from a function call.
 * - toolCall: A tool call the model ran on its own server side.
 * - toolResponse: The output of a server-side tool call.
 */
// note - this class resembles kotlin's data class, but needs to be a regular class to allow for
// deep comparison of the thought signature.
@Serializable
class Part(
  /** Plain text. */
  val text: String? = null,
  /** Binary data (e.g., image, audio). */
  val inlineData: Blob? = null,
  /** Data from a file. */
  val fileData: FileData? = null,
  /** A call to a function. */
  val functionCall: FunctionCall? = null,
  /** The response from a function call. */
  val functionResponse: FunctionResponse? = null,
  /** Indicates whether the part represents the model's thought process. */
  val thought: Boolean? = null,
  /** An opaque signature for the thought. */
  @Serializable(with = LenientByteArraySerializer::class) val thoughtSignature: ByteArray? = null,
  /** Metadata for a video part (segment offsets and frame rate). */
  val videoMetadata: VideoMetadata? = null,
  /** A tool call the model ran on its own server side, to be echoed back on the next request. */
  val toolCall: ToolCall? = null,
  /** The output of a server-side tool call, to be echoed back alongside its [ToolCall]. */
  val toolResponse: ToolResponse? = null,
  /** Arbitrary key-value metadata associated with this part. The map must be JSON serializable. */
  val partMetadata: Map<String, @Contextual Any?>? = null,
) {

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Part) return false

    return text == other.text &&
      inlineData == other.inlineData &&
      fileData == other.fileData &&
      functionCall == other.functionCall &&
      functionResponse == other.functionResponse &&
      thought == other.thought &&
      thoughtSignature.contentEquals(other.thoughtSignature) &&
      videoMetadata == other.videoMetadata &&
      toolCall == other.toolCall &&
      toolResponse == other.toolResponse &&
      partMetadata == other.partMetadata
  }

  override fun hashCode(): Int {
    var result = text?.hashCode() ?: 0
    result = 31 * result + (inlineData?.hashCode() ?: 0)
    result = 31 * result + (fileData?.hashCode() ?: 0)
    result = 31 * result + (functionCall?.hashCode() ?: 0)
    result = 31 * result + (functionResponse?.hashCode() ?: 0)

    result = 31 * result + (thought?.hashCode() ?: 0)
    result = 31 * result + (thoughtSignature?.contentHashCode() ?: 0)
    result = 31 * result + (videoMetadata?.hashCode() ?: 0)
    result = 31 * result + (toolCall?.hashCode() ?: 0)
    result = 31 * result + (toolResponse?.hashCode() ?: 0)
    result = 31 * result + (partMetadata?.hashCode() ?: 0)
    return result
  }

  fun copy(
    text: String? = this.text,
    inlineData: Blob? = this.inlineData,
    fileData: FileData? = this.fileData,
    functionCall: FunctionCall? = this.functionCall,
    functionResponse: FunctionResponse? = this.functionResponse,
    thought: Boolean? = this.thought,
    thoughtSignature: ByteArray? = this.thoughtSignature,
    videoMetadata: VideoMetadata? = this.videoMetadata,
    toolCall: ToolCall? = this.toolCall,
    toolResponse: ToolResponse? = this.toolResponse,
    partMetadata: Map<String, Any?>? = this.partMetadata,
  ): Part =
    Part(
      text,
      inlineData,
      fileData,
      functionCall,
      functionResponse,
      thought,
      thoughtSignature,
      videoMetadata,
      toolCall,
      toolResponse,
      partMetadata,
    )

  override fun toString(): String {
    return "Part(text=$text, inlineData=$inlineData, fileData=$fileData, functionCall=$functionCall, functionResponse=$functionResponse, thought=$thought, thoughtSignature=${thoughtSignature?.contentToString()}, toolCall=$toolCall, toolResponse=$toolResponse)"
  }

  /**
   * Fluent builder for [Part], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var text: String? = null
    private var inlineData: Blob? = null
    private var fileData: FileData? = null
    private var functionCall: FunctionCall? = null
    private var functionResponse: FunctionResponse? = null
    private var thought: Boolean? = null
    private var thoughtSignature: ByteArray? = null
    private var videoMetadata: VideoMetadata? = null
    private var partMetadata: Map<String, @Contextual Any?>? = null
    private var toolCall: ToolCall? = null
    private var toolResponse: ToolResponse? = null

    fun text(text: String?): Builder = apply { this.text = text }

    fun inlineData(inlineData: Blob?): Builder = apply { this.inlineData = inlineData }

    fun fileData(fileData: FileData?): Builder = apply { this.fileData = fileData }

    fun functionCall(functionCall: FunctionCall?): Builder = apply {
      this.functionCall = functionCall
    }

    fun functionResponse(functionResponse: FunctionResponse?): Builder = apply {
      this.functionResponse = functionResponse
    }

    fun thought(thought: Boolean?): Builder = apply { this.thought = thought }

    fun thoughtSignature(thoughtSignature: ByteArray?): Builder = apply {
      this.thoughtSignature = thoughtSignature
    }

    fun videoMetadata(videoMetadata: VideoMetadata?): Builder = apply {
      this.videoMetadata = videoMetadata
    }

    fun partMetadata(partMetadata: Map<String, @Contextual Any?>?): Builder = apply {
      this.partMetadata = partMetadata
    }

    fun toolCall(toolCall: ToolCall?): Builder = apply { this.toolCall = toolCall }

    fun toolResponse(toolResponse: ToolResponse?): Builder = apply {
      this.toolResponse = toolResponse
    }

    fun build(): Part =
      Part(
        text = text,
        inlineData = inlineData,
        fileData = fileData,
        functionCall = functionCall,
        functionResponse = functionResponse,
        thought = thought,
        thoughtSignature = thoughtSignature,
        videoMetadata = videoMetadata,
        partMetadata = partMetadata,
        toolCall = toolCall,
        toolResponse = toolResponse,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
