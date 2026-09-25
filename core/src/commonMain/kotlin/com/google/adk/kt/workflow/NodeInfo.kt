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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmStatic
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNames

/**
 * Identifies the workflow-node activation that emitted an [com.google.adk.kt.events.Event]; `null`
 * outside a workflow.
 *
 * @property path The emitting node's path. Segments are `/`-separated and each is `name@runId`, so
 *   a node of workflow `wf` reads `wf@1/a@1`. An empty path denotes the workflow's top-level node.
 * @property outputFor The node paths this event's output counts for, currently the emitter's own
 *   path. It is set on an event that carries the node's output, either in the event's `output`
 *   field or as a message-as-output event whose content is the output, and the output then counts
 *   for each listed path in the same invocation.
 * @property messageAsOutput When true this event's content *is* the node's output, so no separate
 *   output event follows.
 */
@Serializable
data class NodeInfo(
  val path: String = "",
  @JsonNames("output_for") val outputFor: List<String>? = null,
  @JsonNames("message_as_output") val messageAsOutput: Boolean = false,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder().path(path).outputFor(outputFor).messageAsOutput(messageAsOutput)

  /**
   * Fluent builder for [NodeInfo], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var path: String = ""
    private var outputFor: List<String>? = null
    private var messageAsOutput: Boolean = false

    fun path(path: String): Builder = apply { this.path = path }

    fun outputFor(outputFor: List<String>?): Builder = apply { this.outputFor = outputFor }

    fun messageAsOutput(messageAsOutput: Boolean): Builder = apply {
      this.messageAsOutput = messageAsOutput
    }

    fun build(): NodeInfo =
      NodeInfo(path = path, outputFor = outputFor, messageAsOutput = messageAsOutput)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Decodes an all-empty [NodeInfo] to `null`, matching adk-go. adk-python declares the field
 * non-optional and writes it on every event, so a foreign session would otherwise decode a non-null
 * [NodeInfo] on non-workflow events and break the `null` outside a workflow invariant. Encoding
 * writes `null` as a JSON null and a non-null value through the delegate.
 */
internal object NodeInfoNullIfEmptySerializer : KSerializer<NodeInfo?> {
  private val delegate: KSerializer<NodeInfo?> = NodeInfo.serializer().nullable
  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun serialize(encoder: Encoder, value: NodeInfo?) {
    delegate.serialize(encoder, value)
  }

  override fun deserialize(decoder: Decoder): NodeInfo? =
    delegate.deserialize(decoder)?.takeUnless {
      it.path.isEmpty() && it.outputFor.isNullOrEmpty() && !it.messageAsOutput
    }
}
