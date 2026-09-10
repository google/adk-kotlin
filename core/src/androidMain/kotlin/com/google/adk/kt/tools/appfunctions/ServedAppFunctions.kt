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

package com.google.adk.kt.tools.appfunctions

import kotlin.jvm.Volatile

/**
 * The app functions this process is serving right now, so an agent is not offered one its own app
 * publishes and left able to call itself.
 *
 * Attempts are counted rather than held in a set: two overlapping registrations of one name are
 * ordinary -- a second activity instance, or a rotation whose incoming and outgoing halves overlap
 * -- and with a set the first to finish would clear a name the other is still serving. Counting
 * attempts rather than successes keeps a refused registration from doing the same, at the cost of
 * briefly over-filtering, which is the harmless direction.
 *
 * Advisory, never authoritative. Withdrawal happens as a cancelled coroutine unwinds, on no
 * guaranteed thread, so this can lag the platform in either direction; the platform stays the only
 * authority on whether a name is registered.
 */
internal object ServedAppFunctions {

  private val lock = Any()

  /** Immutable so [isServing] needs no lock; the toolset reads it once per discovered function. */
  @Volatile private var served: Map<String, Int> = emptyMap()

  /** Whether this process is serving [functionId] of [packageName]. */
  fun isServing(packageName: String, functionId: String): Boolean =
    key(packageName, functionId) in served

  /** Records an attempt to serve each of [functionIds] under [packageName]. */
  fun add(packageName: String, functionIds: Set<String>) {
    synchronized(lock) {
      served =
        served.toMutableMap().apply {
          for (functionId in functionIds) {
            val name = key(packageName, functionId)
            this[name] = (this[name] ?: 0) + 1
          }
        }
    }
  }

  /** Records that an attempt to serve each of [functionIds] has ended. */
  fun remove(packageName: String, functionIds: Set<String>) {
    synchronized(lock) {
      served =
        served.toMutableMap().apply {
          for (functionId in functionIds) {
            val name = key(packageName, functionId)
            when (val count = this[name]) {
              null -> {}
              1 -> remove(name)
              else -> this[name] = count - 1
            }
          }
        }
    }
  }

  /** An id is unique only within its package, so the package is part of the identity. */
  private fun key(packageName: String, functionId: String) = "$packageName/$functionId"
}
