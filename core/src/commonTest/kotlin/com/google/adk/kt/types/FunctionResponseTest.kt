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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

class FunctionResponseTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val response =
      FunctionResponse(
        name = "getWeather",
        response = mapOf("temperature" to 21, "note" to null),
        id = "call_1",
        scheduling = FunctionResponseScheduling.WHEN_IDLE,
      )

    assertEquals(response.copy(), response.toBuilder().build())
    assertEquals(response.copy(id = "call_2"), response.toBuilder().id("call_2").build())
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun serialization_writesSchedulingByItsWireNameOnlyWhenSet() {
    val unscheduled = FunctionResponse(name = "f", id = "c1")
    val silent = unscheduled.copy(scheduling = FunctionResponseScheduling.SILENT)

    val silentJson = adkJson.encodeToString(FunctionResponse.serializer(), silent)

    assertEquals(
      """{"name":"f","response":{},"id":"c1"}""",
      adkJson.encodeToString(FunctionResponse.serializer(), unscheduled),
    )
    assertEquals("""{"name":"f","response":{},"id":"c1","scheduling":"SILENT"}""", silentJson)
    assertEquals(silent, adkJson.decodeFromString(FunctionResponse.serializer(), silentJson))
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun decode_withUnknownScheduling_readsUnspecified() {
    val decoded =
      adkJson.decodeFromString(
        FunctionResponse.serializer(),
        """{"name":"f","response":{},"scheduling":"SOMETHING_NEW"}""",
      )

    assertEquals(FunctionResponseScheduling.SCHEDULING_UNSPECIFIED, decoded.scheduling)
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun decode_withNonStringScheduling_fails() {
    assertFailsWith<SerializationException> {
      adkJson.decodeFromString(
        FunctionResponse.serializer(),
        """{"name":"f","response":{},"scheduling":1}""",
      )
    }
  }
}
