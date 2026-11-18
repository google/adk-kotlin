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

import com.google.adk.kt.ValueCoercer
import com.google.adk.kt.types.Schema
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * A thread-safe state map that maintains the current value and tracks modifications.
 *
 * This class implements [Map] to provide a read-only view of the state, while exposing specific
 * mutation methods ([set], [putAll]).
 *
 * @param stateSchema The schema whose properties declare the state keys, or null to check no write.
 *   [set] and [putAll] require a key without a colon to be declared and its value to fit, and store
 *   the value as the schema coerces it, so `"3"` under an `INTEGER` key is stored as `3`.
 */
class State
@JvmOverloads
constructor(
  initialState: Map<String, Any> = emptyMap(),
  initialDelta: Map<String, Any> = emptyMap(),
  internal val stateSchema: Schema? = null,
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

  /**
   * Sets the value for the given key.
   *
   * @throws IllegalArgumentException if the value fails the state schema; the state is unchanged.
   */
  operator fun set(key: String, value: Any): Any? {
    val stored = checkedValue(key, value)
    return lock.write { store(key, stored) }
  }

  /** Clears the state and delta maps. */
  fun clear() = lock.write {
    state.clear()
    delta.clear()
  }

  /**
   * Updates the state with all entries from the given map.
   *
   * @throws IllegalArgumentException if any value fails the state schema; the state is unchanged.
   */
  fun putAll(from: Map<out String, Any>) {
    val stored = from.mapValues { (key, value) -> checkedValue(key, value) }
    lock.write {
      state.putAll(stored)
      delta.putAll(stored)
    }
  }

  fun remove(key: String): Any? = lock.write {
    if (state.containsKey(key)) {
      delta[key] = REMOVED
    }
    state.remove(key)
  }

  /**
   * Applies the non-`temp:` entries of [delta] to the state, tracking them in the delta map. The
   * state schema does not check them, since they record writes already made.
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
          store(key, value)
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
          store(key, value)
        }
      }
    }
  }

  /** Whether the state has pending delta. */
  val hasDelta: Boolean
    get() = lock.read { delta.isNotEmpty() }

  override fun toString(): String = lock.read { state.toString() }

  private fun checkedValue(key: String, value: Any): Any =
    stateSchema?.let { checkStateEntry(it, key, value) } ?: value

  private fun store(key: String, value: Any): Any? {
    val oldValue = state.put(key, value)
    delta[key] = value
    return oldValue
  }

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
    @JvmField val REMOVED: Any = RemovedSentinel

    /** Returns [REMOVED], for Java code written before it was a field. */
    @Deprecated("Read the State.REMOVED field instead.", ReplaceWith("REMOVED"))
    @JvmStatic
    @Suppress("FunctionName")
    fun getREMOVED(): Any = REMOVED
  }
}

/**
 * Checks one state write against [schema] and returns the value to store. Any key containing a
 * colon passes unchecked, as in ADK Python, since ADK keeps its own `<owner>:<key>` state beside
 * the `app:`, `user:` and `temp:` scopes. So does the removal sentinel, which stands for absence.
 */
internal fun checkStateEntry(schema: Schema, key: String, value: Any): Any {
  if (':' in key || value === State.REMOVED) return value
  val propertySchema =
    requireNotNull(schema.properties?.get(key)) {
      "validation error: state key '$key' is not declared in the state schema."
    }
  val checked = ValueCoercer.coerceValue(value, propertySchema, "state key '$key'").getOrThrow()
  return requireNotNull(checked) { "validation error: state key '$key' is null." }
}

/**
 * The three prefix scopes an initial-state map splits into: the shared `app:` and `user:` maps
 * (with their prefixes stripped) and this session's own state. `temp:` entries are dropped, since
 * they live for one invocation and could never be read back.
 */
internal data class ScopedInitialState(
  val appState: Map<String, Any>,
  val userState: Map<String, Any>,
  val sessionState: Map<String, Any>,
)

/**
 * Splits an initial-state map by [State] prefix into its `app:`, `user:` and session scopes - the
 * classification half of `createSession` scoping, factored out from the promotion into storage,
 * which differs per backend. Mirrors Python's `extract_state_delta`. Drop any [State.REMOVED]
 * sentinel before calling: creating a session must not erase a key another session owns, whereas an
 * append delta still honors it.
 */
internal fun splitInitialStateByScope(state: Map<String, Any>): ScopedInitialState {
  val appState = mutableMapOf<String, Any>()
  val userState = mutableMapOf<String, Any>()
  val sessionState = mutableMapOf<String, Any>()
  for ((key, value) in state) {
    when {
      key.startsWith(State.APP_PREFIX) -> appState[key.removePrefix(State.APP_PREFIX)] = value
      key.startsWith(State.USER_PREFIX) -> userState[key.removePrefix(State.USER_PREFIX)] = value
      key.startsWith(State.TEMP_PREFIX) -> {}
      else -> sessionState[key] = value
    }
  }
  return ScopedInitialState(appState, userState, sessionState)
}

/**
 * A scope of state that several sessions share: a user's `user:` state, or an app's `app:` state.
 */
internal sealed interface SharedStateScope {
  /** The `user:` state of [userId] in [appName]. */
  data class User(val appName: String, val userId: String) : SharedStateScope

  /** The `app:` state of [appName]. */
  data class App(val appName: String) : SharedStateScope

  /** Whether the session [key] sees this scope's state. */
  fun isSeenBy(key: SessionKey): Boolean =
    when (this) {
      is User -> appName == key.appName && userId == key.userId
      is App -> appName == key.appName
    }

  companion object {
    /** Returns the shared scopes that a change to [stateKeys] in the session [key] writes to. */
    fun changedBy(key: SessionKey, stateKeys: Iterable<String>): Set<SharedStateScope> = buildSet {
      if (stateKeys.any { it.startsWith(State.USER_PREFIX) }) add(User(key.appName, key.userId))
      if (stateKeys.any { it.startsWith(State.APP_PREFIX) }) add(App(key.appName))
    }

    /** Returns the shared scopes that every session of [userId] in [appName] sees. */
    fun seenBy(appName: String, userId: String): Set<SharedStateScope> =
      setOf(User(appName, userId), App(appName))
  }
}
