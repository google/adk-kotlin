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
package com.google.adk.kt.models

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(AdkJavaInteropApi::class)
class LlmCapabilitiesTest {

  @Test
  fun toBuilder_build_equalsCopy() {
    // Arrange: every property set to a non-default value.
    val capabilities = LlmCapabilities(outputSchemaAndTools = true)

    // Act & Assert
    assertEquals(capabilities.copy(), capabilities.toBuilder().build())
  }

  @Test
  fun builder_build_equalsTheDefaultConstructor() {
    assertEquals(LlmCapabilities(), LlmCapabilities.builder().build())
  }
}
