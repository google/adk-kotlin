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

package com.google.adk.kt.sessions

/**
 * A thread-safe state map that maintains the current value and tracks modifications.
 *
 * This class implements [Map] to provide a read-only view of the state, while exposing specific
 * mutation methods ([set], [putAll]).
 */
class State(
  initialState: Map<String, Any> = emptyMap(),
  initialDelta: Map<String, Any> = emptyMap(),
) : Map<String, Any> {
  // Ensure we make a copy of the initial state and delta maps to avoid modifying the caller's
  // maps.
  private val state: MutableMap<String, Any> = initialState.toMutableMap()
  private val delta: MutableMap<String, Any> = initialDelta.toMutableMap()

  private val lock = Lock()

  override val entries: Set<Map.Entry<String, Any>>
    get() = lock.read { state.entries.toSet() }

  override val keys: Set<String>
    get() = lock.read { state.keys.toSet() }

  override val size: Int
    get() = lock.read { state.size }

  override val values: Collection<Any>
    get() = lock.read { state.values.toList() }

  override fun isEmpty(): Boolean = lock.read { state.isEmpty() }

  override fun containsKey(key: String): Boolean = lock.read { state.containsKey(key) }

  override fun containsValue(value: Any): Boolean = lock.read { state.containsValue(value) }

  override operator fun get(key: String): Any? = lock.read { state[key] }

  /** Sets the value for the given key. */
  operator fun set(key: String, value: Any): Any? = lock.write {
    val oldValue = state.put(key, value)
    delta[key] = value
    oldValue
  }

  /** Clears the state and delta maps. */
  fun clear() = lock.write {
    state.clear()
    delta.clear()
  }

  /** Updates the state with all entries from the given map. */
  fun putAll(from: Map<out String, Any>) = lock.write {
    state.putAll(from)
    delta.putAll(from)
  }

  fun remove(key: String): Any? = lock.write {
    if (state.containsKey(key)) {
      delta[key] = REMOVED
    }
    state.remove(key)
  }

  /**
   * Applies the non-`temp:` entries of [delta] to the state, tracking them in the delta map.
   *
   * `temp:` entries are skipped; apply those with [applyTempDelta].
   */
  fun applyDelta(delta: Map<String, Any>) {
    lock.write {
      for ((key, value) in delta) {
        if (key.startsWith(TEMP_PREFIX)) {
          continue
        }

        if (value === REMOVED) {
          remove(key)
        } else {
          this[key] = value
        }
      }
    }
  }

  /**
   * Applies only the `temp:`-prefixed entries of [delta] to the state, tracking them in the delta
   * map; the complement of [applyDelta].
   */
  fun applyTempDelta(delta: Map<String, Any>) {
    lock.write {
      for ((key, value) in delta) {
        if (!key.startsWith(TEMP_PREFIX)) {
          continue
        }

        if (value === REMOVED) {
          remove(key)
        } else {
          this[key] = value
        }
      }
    }
  }

  /** Whether the state has pending delta. */
  val hasDelta: Boolean
    get() = lock.read { delta.isNotEmpty() }

  override fun toString(): String = lock.read { state.toString() }

  private object RemovedSentinel {
    override fun toString(): String = "__ADK_SENTINEL_REMOVED__"
  }

  companion object {
    /** Prefix for state variables that are shared across sessions of the same application. */
    const val APP_PREFIX = "app:"

    /** Prefix for state variables that are shared across sessions of the same user. */
    const val USER_PREFIX = "user:"

    /** Prefix for temporary state variables that should not be persisted. */
    const val TEMP_PREFIX = "temp:"

    /** Sentinel object to mark removed entries in the delta map. */
    val REMOVED: Any = RemovedSentinel
  }
}
