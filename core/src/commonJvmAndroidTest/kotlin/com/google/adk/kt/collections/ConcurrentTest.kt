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

package com.google.adk.kt.collections

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrentTest {
  @Test
  fun concurrentMutableMapOf_isEmpty() {
    val map = ConcurrentHashMap<String, Int>()
    assertTrue(map.isEmpty())
  }

  @Test
  fun concurrentMutableMapOf_canStoreValues() {
    val map = ConcurrentHashMap<String, Int>()
    map["test"] = 1
    assertTrue(map.containsKey("test"))
    assertTrue(map["test"] == 1)
  }

  @Test
  fun concurrentMutableListOf_storesAndIteratesValues() {
    val list = concurrentMutableListOf<Int>()
    list.add(1)
    list.add(2)
    list.add(3)
    assertEquals(listOf(1, 2, 3), list.toList())
  }

  @Test
  fun concurrentMutableListOf_addDuringIteration_doesNotThrow() {
    val list = concurrentMutableListOf<Int>()
    list.addAll(listOf(1, 2, 3))

    // A fail-fast list (ArrayList, or a bare synchronizedList) throws
    // ConcurrentModificationException when advanced after a structural change. A concurrent list
    // gives a snapshot iterator, so the traversal completes and sees the original three elements.
    val iterator = list.iterator()
    iterator.next()
    list.add(4)

    var count = 1
    while (iterator.hasNext()) {
      iterator.next()
      count++
    }
    assertEquals(3, count)
    assertEquals(4, list.size)
  }
}
