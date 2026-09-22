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
package com.google.adk.kt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

/**
 * Wraps [source] so only one collection runs at a time; collecting while another is active throws
 * [IllegalStateException] with [message], and collecting again after one ends is allowed.
 *
 * [guard] belongs to the caller because it must outlive any one returned flow: a caller handing out
 * a fresh flow per call would otherwise get a fresh guard with it and protect nothing.
 *
 * [source] is a lambda so that each collection builds its own upstream rather than sharing one.
 */
internal fun <T> singleCollectorFlow(
  guard: Mutex,
  message: String,
  source: () -> Flow<T>,
): Flow<T> = flow {
  check(guard.tryLock()) { message }
  try {
    emitAll(source())
  } finally {
    guard.unlock()
  }
}
