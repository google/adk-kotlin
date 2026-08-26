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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * A value on a workflow edge, and the value a node emits to select which of its outgoing edges are
 * followed. An edge fires when the value it carries matches one the node emitted.
 *
 * On the wire a route is a bare scalar - a string, integer or boolean - and [Default] is the
 * [DEFAULT_ROUTE_SENTINEL] string, matching adk-python.
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
    /** The sentinel adk-python uses on the wire for [Default]. */
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
    val jsonEncoder =
      encoder as? JsonEncoder ?: throw SerializationException("Route supports JSON formats only.")
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
    val jsonDecoder =
      decoder as? JsonDecoder ?: throw SerializationException("Route supports JSON formats only.")
    val primitive =
      jsonDecoder.decodeJsonElement() as? JsonPrimitive
        ?: throw SerializationException("A route value must be a JSON primitive.")
    return when {
      primitive.isString ->
        if (primitive.content == Route.DEFAULT_ROUTE_SENTINEL) Route.Default
        else Route.Tag(primitive.content)
      primitive.booleanOrNull != null -> Route.Flag(primitive.boolean)
      primitive.longOrNull != null -> Route.Num(primitive.long)
      else -> throw SerializationException("Unsupported route value type.")
    }
  }
}

/**
 * Decodes [com.google.adk.kt.events.EventActions.route] from either a JSON array of scalar routes
 * or a single bare scalar, so a foreign session that emits one route as a bare scalar (adk-python's
 * single-route wire form) still loads. Encoding always emits a JSON array of scalar routes. JSON
 * formats only.
 */
internal object RouteListSerializer : KSerializer<List<Route>> {
  private val delegate = ListSerializer(RouteSerializer)
  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun serialize(encoder: Encoder, value: List<Route>) {
    encoder.encodeSerializableValue(delegate, value)
  }

  override fun deserialize(decoder: Decoder): List<Route> {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw SerializationException("Route list supports JSON formats only.")
    val element = jsonDecoder.decodeJsonElement()
    val routes = if (element is JsonArray) element else listOf(element)
    return routes.map { jsonDecoder.json.decodeFromJsonElement(RouteSerializer, it) }
  }
}
