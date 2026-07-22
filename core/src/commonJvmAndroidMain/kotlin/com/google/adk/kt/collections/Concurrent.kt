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
import java.util.concurrent.CopyOnWriteArrayList

actual fun <K : Any, V : Any> concurrentMutableMapOf(): MutableMap<K, V> = ConcurrentHashMap()

// CopyOnWriteArrayList gives snapshot iterators, so a reader traversing the list never sees a
// ConcurrentModificationException from a concurrent add. Writes copy the backing array, which is
// fine for the append-mostly, read-often session event history.
actual fun <T> concurrentMutableListOf(): MutableList<T> = CopyOnWriteArrayList()
