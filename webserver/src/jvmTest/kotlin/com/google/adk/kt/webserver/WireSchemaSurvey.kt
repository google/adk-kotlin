/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal

/**
 * A class or object the survey reached, and the path of the reference that reached it. Each `T?`
 * property has its own nullable descriptor, so a class gets an entry per nullable reference besides
 * the one for its first non-null reference.
 */
internal data class SurveyedType(val path: String, val type: SerialDescriptor)

/**
 * Walks the descriptor graph under each of [roots], reporting paths from its name, so a wire
 * coverage test can hold every reachable type to its rule, including types no fixture populates.
 * Free-form values carry caller data, not schema, so the survey declines them instead of entering.
 */
internal class WireSchemaSurvey(roots: Map<String, KSerializer<*>>) {
  private val _types = mutableListOf<SurveyedType>()
  private val _declined = mutableSetOf<SerialDescriptor>()
  // By identity, not serial name, which List<A> and List<B> share; a recursive generic would loop.
  private val seen = Collections.newSetFromMap(IdentityHashMap<SerialDescriptor, Boolean>())

  init {
    for ((root, serializer) in roots) {
      seen.clear()
      enter(serializer.descriptor, root)
    }
  }

  /** A sealed hierarchy's variants share their parent's path. */
  val types: List<SurveyedType>
    get() = _types

  val declined: Set<SerialDescriptor>
    get() = _declined

  private fun enter(descriptor: SerialDescriptor, path: String) {
    // Before unwrapping: every `T?` property has its own wrapper, so each is surveyed separately.
    if (!seen.add(descriptor)) return
    val type = descriptor.nonNullOriginal
    // An inline value class contributes its wrapped type, not a nesting level.
    if (type.isInline) return enter(type.getElementDescriptor(0), path)
    when (type.kind) {
      StructureKind.LIST -> enter(type.getElementDescriptor(0), "$path[]")
      // A map's keys are caller data, but its values may still be a schema type.
      StructureKind.MAP -> enter(type.getElementDescriptor(1), "$path{}")
      StructureKind.CLASS,
      StructureKind.OBJECT -> enterFields(type, path)
      PolymorphicKind.SEALED -> enterVariants(type, path)
      SerialKind.CONTEXTUAL,
      PolymorphicKind.OPEN -> _declined += type
      // Primitives and enums name no schema keys.
      else -> {}
    }
  }

  private fun enterFields(type: SerialDescriptor, path: String) {
    _types += SurveyedType(path, type)
    for (index in 0 until type.elementsCount) {
      enter(type.getElementDescriptor(index), "$path.${type.getElementName(index)}")
    }
  }

  /**
   * A sealed serializer holds its variants in element 1; one with any other element count, such as
   * `JsonElement`'s, is declined so that a pin fails rather than the survey skipping it.
   */
  private fun enterVariants(type: SerialDescriptor, path: String) {
    if (type.elementsCount != 2) {
      _declined += type
      return
    }
    val variants = type.getElementDescriptor(1)
    for (index in 0 until variants.elementsCount) enter(variants.getElementDescriptor(index), path)
  }
}
