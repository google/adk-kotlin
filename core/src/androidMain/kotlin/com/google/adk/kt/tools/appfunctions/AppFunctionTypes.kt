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

import androidx.appfunctions.metadata.AppFunctionAllOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata

/**
 * Reading of the AppFunction type model that both the schema and the data conversion need.
 *
 * Both sides have to follow a reference to the type it names and see an all-of as the single object
 * it describes, and both have to do it without trusting another app's metadata to terminate.
 */
internal object AppFunctionTypes {

  /**
   * How deeply an all-of may nest another all-of written out in place.
   *
   * The composition arrives from another app, so the recursion is bounded rather than trusted to
   * fit the stack it runs on.
   */
  private const val MAX_ALL_OF_DEPTH = 32

  /**
   * Follows a chain of references to the type it names, or `null` when it does not resolve.
   *
   * A chain that reaches a name already on it does not resolve: a reference to itself names no
   * type. The whole chain is followed because that is how the library resolves an ordinary
   * property's type, unlike the single hop it applies to an all-of member.
   */
  fun resolve(
    type: AppFunctionDataTypeMetadata,
    components: AppFunctionComponentsMetadata,
  ): AppFunctionDataTypeMetadata? {
    var current = type
    val seen = mutableSetOf<String>()
    while (current is AppFunctionReferenceTypeMetadata) {
      if (!seen.add(current.referenceDataType)) return null
      current = components.dataTypes[current.referenceDataType] ?: return null
    }
    return current
  }

  /** The qualified name the library gives a `PendingIntent` return. */
  private const val PENDING_INTENT_NAME = "android.app.PendingIntent"

  /**
   * Whether [type] is a screen for the caller to open rather than data.
   *
   * A function that returns one is doing the over-the-app half of AppFunctions: the result is a
   * `PendingIntent` the caller sends, not a value the model can read.
   */
  fun isPendingIntent(
    type: AppFunctionDataTypeMetadata,
    components: AppFunctionComponentsMetadata,
  ): Boolean {
    val resolved = resolve(type, components)
    return resolved is AppFunctionParcelableTypeMetadata &&
      resolved.qualifiedName == PENDING_INTENT_NAME
  }

  /** How many type nodes one undeliverable-screen scan may visit before it gives up. */
  private const val MAX_PENDING_INTENT_NODES = 1024

  /** Whether [type] holds a screen somewhere inside it, where nothing can read or deliver it. */
  fun carriesUndeliverablePendingIntent(
    type: AppFunctionDataTypeMetadata,
    components: AppFunctionComponentsMetadata,
  ): Boolean {
    val budget = intArrayOf(MAX_PENDING_INTENT_NODES)
    return when (val resolved = resolve(type, components)) {
      // [isPendingIntent] has already excluded a bare screen: such a function is never offered.
      is AppFunctionParcelableTypeMetadata -> false
      is AppFunctionArrayTypeMetadata ->
        nestsPendingIntent(resolved.itemType, components, budget, 0)
      is AppFunctionObjectTypeMetadata ->
        resolved.properties.values.any { nestsPendingIntent(it, components, budget, 0) }
      is AppFunctionAllOfTypeMetadata ->
        // A composition that will not merge cannot be checked, so it counts as undeliverable.
        flattenAllOf(resolved, components, budget)?.properties?.values?.any {
          nestsPendingIntent(it, components, budget, 0)
        } ?: true
      else -> false
    }
  }

  /**
   * Whether [type] resolves to a `PendingIntent` or holds one inside an array or object.
   *
   * Unlike the top-level scan, a bare `PendingIntent` here counts: it sits nested rather than as
   * the return value, so no reader can reach it. The metadata is another app's, so the walk is
   * bounded by the same depth cap and shared budget the all-of merge uses, and exhausting either
   * answers "yes" rather than waving through metadata too big to check.
   */
  private fun nestsPendingIntent(
    type: AppFunctionDataTypeMetadata,
    components: AppFunctionComponentsMetadata,
    budget: IntArray,
    depth: Int,
  ): Boolean {
    // Fails closed, like every other cap here: unchecked is treated as undeliverable.
    if (depth >= MAX_ALL_OF_DEPTH || budget[0] <= 0) return true
    budget[0]--
    return when (val resolved = resolve(type, components)) {
      is AppFunctionParcelableTypeMetadata -> resolved.qualifiedName == PENDING_INTENT_NAME
      is AppFunctionArrayTypeMetadata ->
        nestsPendingIntent(resolved.itemType, components, budget, depth + 1)
      is AppFunctionObjectTypeMetadata ->
        resolved.properties.values.any { nestsPendingIntent(it, components, budget, depth + 1) }
      is AppFunctionAllOfTypeMetadata ->
        flattenAllOf(resolved, components, budget, depth + 1)?.properties?.values?.any {
          nestsPendingIntent(it, components, budget, depth + 1)
        } ?: true
      else -> false
    }
  }

  /**
   * The single object an all-of describes, or `null` when it does not merge into one.
   *
   * A member is read exactly as the app's own validation reads it, which is deliberately narrower
   * than [resolve]: a reference is followed one hop and contributes only if an object sits at the
   * end of it. Merging any harder would offer the model properties the app then rejects, since the
   * composition is checked against the library's reading and not ours.
   *
   * @param remaining members still merge-able. Required rather than defaulted, because one
   *   composition is re-merged on every path that reaches it and a fresh budget per call would
   *   restore the blow-up the cap exists to stop.
   */
  fun flattenAllOf(
    type: AppFunctionAllOfTypeMetadata,
    components: AppFunctionComponentsMetadata,
    remaining: IntArray,
    depth: Int = 0,
  ): AppFunctionObjectTypeMetadata? {
    if (depth >= MAX_ALL_OF_DEPTH) return null
    val properties = mutableMapOf<String, AppFunctionDataTypeMetadata>()
    val required = mutableSetOf<String>()
    for (member in type.matchAll) {
      if (remaining[0] <= 0) return null
      remaining[0]--
      val merged: AppFunctionObjectTypeMetadata? =
        when (member) {
          is AppFunctionReferenceTypeMetadata ->
            when (val target = components.dataTypes[member.referenceDataType]) {
              // The library throws on a reference it cannot resolve; refuse the composition.
              null -> return null
              is AppFunctionObjectTypeMetadata -> target
              // A further reference, or a composition, is where the app's own reading stops.
              else -> null
            }
          is AppFunctionObjectTypeMetadata -> member
          is AppFunctionAllOfTypeMetadata ->
            flattenAllOf(member, components, remaining, depth + 1) ?: return null
          // A member that is not an object contributes no properties of its own.
          else -> null
        }
      if (merged == null) continue
      properties.putAll(merged.properties)
      required.addAll(merged.required)
    }
    return AppFunctionObjectTypeMetadata(
      properties = properties,
      required = required.toList(),
      qualifiedName = type.qualifiedName,
      // Fixed, not carried over from the composition: the app builds its spec from the library's
      // pseudo object, which hardcodes both, and the two must compare equal.
      isNullable = false,
      description = "",
    )
  }
}
