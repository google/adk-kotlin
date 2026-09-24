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
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionCallTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val call =
      FunctionCall(
        name = "getWeather",
        args = mapOf("city" to "Paris", "unit" to null),
        id = "call_1",
        partialArgs = listOf(PartialArg(jsonPath = "\$.city")),
        willContinue = true,
      )

    assertEquals(call.copy(), call.toBuilder().build())
    assertEquals(call.copy(id = "call_2"), call.toBuilder().id("call_2").build())
  }
}
