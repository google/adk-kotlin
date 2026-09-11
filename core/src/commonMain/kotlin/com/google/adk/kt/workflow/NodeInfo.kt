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
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Identifies the workflow-node activation that emitted an [Event]; `null` outside a workflow.
 *
 * @property path The emitting node's path. Segments are `/`-separated and each is `name@runId`, so
 *   a node of workflow `wf` reads `wf@1/a@1`. It is empty only outside a workflow, since the field
 *   defaults to "" for a non-workflow event.
 * @property outputFor The node paths this event's output counts for: the emitter's own path plus
 *   any delegating ancestor, since a terminal node's output is also its workflow's. It is set only
 *   on an event that carries an output value, and the output then counts for each listed path in
 *   the same invocation.
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
 * A value on a workflow edge, and the value a node emits to select which of its outgoing edges are
 * followed. An edge fires when the value it carries matches one the node emitted.
 *
 * On the wire a route is a bare scalar - a string, integer or boolean - and [Default] is the
 * [DEFAULT_ROUTE_SENTINEL] string, matching the other ADK implementations.
 */
@Serializable(with = RouteSerializer::class)
sealed interface Route {

  /** A route identified by a string, the common case. */
  data class Tag(val value: String) : Route

  /** A route identified by an integer. */
  data class Num(val value: Long) : Route

  /** A route identified by a boolean, for a two-way branch. */
  data class Flag(val value: Boolean) : Route

  /**
   * The fallback edge, followed when a node emits a route that no other outgoing edge matches. A
   * node may declare at most one, and it cannot share an edge with a concrete route.
   */
  data object Default : Route

  companion object {
    /** The sentinel the other ADK implementations use on the wire for [Default]. */
    const val DEFAULT_ROUTE_SENTINEL: String = "__DEFAULT__"
  }
}

/**
 * Serializes a [Route] as a bare JSON scalar: the concrete routes as their primitive value and
 * [Route.Default] as the [Route.DEFAULT_ROUTE_SENTINEL] string. JSON formats only.
 */
internal object RouteSerializer : KSerializer<Route> {
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

  override fun serialize(encoder: Encoder, value: Route) {
    val jsonEncoder = encoder as? JsonEncoder ?: error("Route supports JSON formats only.")
    val element =
      when (value) {
        is Route.Tag -> JsonPrimitive(value.value)
        is Route.Num -> JsonPrimitive(value.value)
        is Route.Flag -> JsonPrimitive(value.value)
        Route.Default -> JsonPrimitive(Route.DEFAULT_ROUTE_SENTINEL)
      }
    jsonEncoder.encodeJsonElement(element)
  }

  override fun deserialize(decoder: Decoder): Route {
    val jsonDecoder = decoder as? JsonDecoder ?: error("Route supports JSON formats only.")
    val primitive =
      jsonDecoder.decodeJsonElement() as? JsonPrimitive
        ?: throw IllegalArgumentException("A route value must be a JSON primitive.")
    return when {
      primitive.isString ->
        if (primitive.content == Route.DEFAULT_ROUTE_SENTINEL) Route.Default
        else Route.Tag(primitive.content)
      primitive.booleanOrNull != null -> Route.Flag(primitive.booleanOrNull!!)
      primitive.longOrNull != null -> Route.Num(primitive.longOrNull!!)
      else -> throw IllegalArgumentException("Unsupported route value: ${primitive.content}")
    }
  }
}
