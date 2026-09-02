/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Inner message payload of a Pub/Sub push delivery. */
@Serializable
data class PubSubMessage(
  /** Base64-encoded message data. */
  val data: String? = null,
  /** Message attributes. */
  val attributes: Map<String, String>? = null,
  /** Pub/Sub message ID. */
  val messageId: String? = null,
  /** Publish timestamp. */
  val publishTime: String? = null,
)

/**
 * Body of a Pub/Sub push subscription delivery.
 *
 * See https://cloud.google.com/pubsub/docs/push#receive_push.
 */
@Serializable
data class PubSubTriggerRequest(
  val message: PubSubMessage,
  /** Full subscription name, e.g. `projects/p/subscriptions/s`. */
  val subscription: String? = null,
)

/**
 * Body of an Eventarc delivery, in either CloudEvents content mode.
 *
 * In structured mode the caller puts every CloudEvents attribute and the event payload in the JSON
 * body, so [data] is set. In binary mode, which is what Eventarc itself sends, the attributes
 * travel as `ce-*` headers and the body holds only the payload -- for a Pub/Sub-sourced event that
 * payload is a Pub/Sub wrapper, so [message] and [subscription] are set instead.
 *
 * See https://cloud.google.com/eventarc/docs/cloudevents.
 */
@Serializable
data class EventarcTriggerRequest(
  /** Event payload, in structured content mode. */
  val data: JsonObject? = null,
  /** CloudEvents `source` attribute. */
  val source: String? = null,
  /** CloudEvents `type` attribute. */
  val type: String? = null,
  /** CloudEvents `id` attribute. */
  val id: String? = null,
  /** CloudEvents `time` attribute. */
  val time: String? = null,
  /** CloudEvents `specversion` attribute. */
  @SerialName("specversion") val specVersion: String? = null,
  /** Pub/Sub message wrapper, in binary content mode. */
  val message: PubSubMessage? = null,
  /** Pub/Sub subscription name, in binary content mode. */
  val subscription: String? = null,
)

/** Response body of every trigger endpoint. */
@Serializable
data class TriggerResponse(val status: Status) {
  /** Whether the trigger ran its agent. */
  @Serializable
  enum class Status {
    @SerialName("success") SUCCESS,
    @SerialName("error") ERROR,
  }
}
