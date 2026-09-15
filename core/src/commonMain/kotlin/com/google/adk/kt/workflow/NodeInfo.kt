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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Identifies the workflow-node activation that emitted an [com.google.adk.kt.events.Event]; `null`
 * outside a workflow.
 *
 * @property path The emitting node's path. Segments are `/`-separated and each is `name@runId`, so
 *   a node of workflow `wf` reads `wf@1/a@1`. An empty path denotes the workflow's top-level node.
 * @property outputFor The node paths this event's output counts for, currently the emitter's own
 *   path. It is set only on an event that carries an output value, and the output then counts for
 *   each listed path in the same invocation.
 * @property messageAsOutput When true this event's content *is* the node's output, so no separate
 *   output event follows.
 */
@Serializable
data class NodeInfo(
  val path: String = "",
  val outputFor: List<String>? = null,
  val messageAsOutput: Boolean = false,
)

/**
 * Decodes an all-empty [NodeInfo] to `null`, matching adk-go. adk-python declares the field
 * non-optional and writes it on every event, so a foreign session would otherwise decode a non-null
 * [NodeInfo] on non-workflow events and break the `null` outside a workflow invariant. Encoding
 * writes `null` as a JSON null and a non-null value through the delegate.
 */
internal object NodeInfoNullIfEmptySerializer : KSerializer<NodeInfo?> {
  private val delegate = NodeInfo.serializer()
  override val descriptor: SerialDescriptor = delegate.descriptor.nullable

  override fun serialize(encoder: Encoder, value: NodeInfo?) {
    if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(delegate, value)
  }

  override fun deserialize(decoder: Decoder): NodeInfo? {
    // The nullable descriptor makes this serializer null-aware, so the null token arrives here.
    if (!decoder.decodeNotNullMark()) return decoder.decodeNull()
    return delegate.deserialize(decoder).takeUnless {
      it.path.isEmpty() && it.outputFor.isNullOrEmpty() && !it.messageAsOutput
    }
  }
}
