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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Pins the wire format of the trigger endpoints, decoded through the same `Json` the server
 * installs. Property names here are Kotlin-side; what the caller sends is what these assert.
 */
@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class TriggerModelsTest {

  @Test
  fun pubSubDelivery_readsTheFieldsPubSubActuallySends() {
    val body =
      """
      {
        "message": {
          "data": "aGVsbG8=",
          "attributes": {"origin": "checkout"},
          "messageId": "2070443601311540",
          "publishTime": "2026-02-26T19:13:55.749Z"
        },
        "subscription": "projects/p/subscriptions/s"
      }
      """
        .trimIndent()

    val request = adkJson.decodeFromString<PubSubTriggerRequest>(body)

    assertThat(request.message.data).isEqualTo("aGVsbG8=")
    assertThat(request.message.attributes).containsExactly("origin", "checkout")
    assertThat(request.message.messageId).isEqualTo("2070443601311540")
    assertThat(request.message.publishTime).isEqualTo("2026-02-26T19:13:55.749Z")
    assertThat(request.subscription).isEqualTo("projects/p/subscriptions/s")
  }

  @Test
  fun pubSubDelivery_survivesAFieldTheModelDoesNotDeclare() {
    val body = """{"message": {"data": "aGk="}, "deliveryAttempt": 3}"""

    val request = adkJson.decodeFromString<PubSubTriggerRequest>(body)

    assertThat(request.message.data).isEqualTo("aGk=")
  }

  @Test
  fun eventarcDelivery_readsSpecversionUnderItsCloudEventsName() {
    val body =
      """{"specversion": "1.0", "id": "e-1", "type": "google.cloud.pubsub.topic.v1.messagePublished"}"""

    val request = adkJson.decodeFromString<EventarcTriggerRequest>(body)

    assertThat(request.specVersion).isEqualTo("1.0")
    assertThat(request.id).isEqualTo("e-1")
    assertThat(request.type).isEqualTo("google.cloud.pubsub.topic.v1.messagePublished")
  }

  @Test
  fun eventarcDelivery_readsTheBinaryModePubSubWrapper() {
    val body = """{"message": {"data": "aGk="}, "subscription": "projects/p/subscriptions/s"}"""

    val request = adkJson.decodeFromString<EventarcTriggerRequest>(body)

    assertThat(request.data).isNull()
    assertThat(request.message?.data).isEqualTo("aGk=")
    assertThat(request.subscription).isEqualTo("projects/p/subscriptions/s")
  }

  @Test
  fun triggerResponse_encodesItsStatusInLowerCase() {
    assertThat(adkJson.encodeToString(TriggerResponse(TriggerResponse.Status.SUCCESS)))
      .isEqualTo("""{"status":"success"}""")
    assertThat(adkJson.encodeToString(TriggerResponse(TriggerResponse.Status.ERROR)))
      .isEqualTo("""{"status":"error"}""")
  }
}
