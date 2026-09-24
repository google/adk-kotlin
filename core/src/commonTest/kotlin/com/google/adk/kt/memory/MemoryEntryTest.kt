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

package com.google.adk.kt.memory

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryEntryTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val entry =
      MemoryEntry(
        content = Content(parts = listOf(Part(text = "I like tea."))),
        id = "memory_1",
        author = "user",
        timestamp = "2026-01-01T00:00:00Z",
        customMetadata = mapOf("topic" to "drinks"),
      )

    assertEquals(entry.copy(), entry.toBuilder().build())
    assertEquals(entry.copy(author = "model"), entry.toBuilder().author("model").build())
  }
}
