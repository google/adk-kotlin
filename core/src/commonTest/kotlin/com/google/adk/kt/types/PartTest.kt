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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PartTest {
  @Test
  fun equals_sameData_returnsTrue() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))

    assertEquals(part1, part2)
  }

  @Test
  fun equals_differentData_returnsFalse() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 4))

    assertNotEquals(part1, part2)
  }

  @Test
  fun equals_oneDataNull_returnsFalse() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = null)

    assertNotEquals(part1, part2)
  }

  @Test
  fun equals_bothDataNull_returnsTrue() {
    val part1 = Part(thought = true, thoughtSignature = null)
    val part2 = Part(thought = true, thoughtSignature = null)

    assertEquals(part1, part2)
  }

  @Test
  fun hashCode_sameData_returnsSameHashCode() {
    val part1 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))
    val part2 = Part(thought = true, thoughtSignature = byteArrayOf(1, 2, 3))

    assertEquals(part1.hashCode(), part2.hashCode())
  }
}
