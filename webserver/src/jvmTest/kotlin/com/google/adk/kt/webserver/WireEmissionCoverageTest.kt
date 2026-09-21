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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.models.SessionDto
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Holds the emission rule to the response types themselves, so a snake_case key is caught on a
 * field no response happens to populate - which an endpoint test structurally cannot see.
 *
 * The rule governs the keys the schema introduces, not a free-form map's keys, which are caller or
 * model data. Every check is therefore driven by a [SerialDescriptor], which knows the difference,
 * rather than by scanning response text, which does not. [WireEndpointTest] exercises the same rule
 * against real responses; [WireAliasCoverageTest] is the intake counterpart of this survey.
 */
@OptIn(ExperimentalSerializationApi::class, FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class WireEmissionCoverageTest {

  /**
   * The types the ADK agent runtime endpoints emit: `/version`, the session routes, the artifact
   * routes, and `/run` and `/run_sse`. `/health` and `/list-apps` emit no schema of their own.
   */
  private val responseRoots: Map<String, KSerializer<*>> =
    mapOf(
      "VersionInfo" to VersionInfo.serializer(),
      "SessionDto" to SessionDto.serializer(),
      "Part" to Part.serializer(),
      "Event" to ListSerializer(Event.serializer()),
    )

  @Test
  fun noSchemaKeyOnTheResponseGraph_isSnakeCase() {
    val survey = surveyAllRoots()

    assertThat(survey.snakeCase).containsExactlyElementsIn(KNOWN_LEGACY_ALIASES.keys)
    assertThat(survey.visited).containsAtLeastElementsIn(KNOWN_LEGACY_ALIASES.values)
  }

  @Test
  fun theSurvey_reachesTheTypesItClaimsTo() {
    val survey = surveyAllRoots()

    assertThat(survey.visited)
      .containsAtLeast(
        "VersionInfo.languageVersion",
        "Part.inlineData.mimeType",
        "Event[].content.parts[].functionCall.name",
        "Event[].actions.requestedToolConfirmations{}.hint",
        "Event[].content.parts[].functionCall.partialArgs[].value.value",
      )
  }

  @Test
  fun theSurvey_declinesOnlyFreeFormValues() {
    // A declined type is a blind spot, so what gets declined is pinned rather than trusted. Pinned
    // by kind, not by serial name, which is a kotlinx-internal string a library bump could rename.
    val survey = surveyAllRoots()

    assertThat(survey.declinedKinds).containsExactly(SerialKind.CONTEXTUAL)
    assertThat(survey.declined).isNotEmpty()
  }

  @Test
  fun adkJson_stillOmitsNullFields() {
    // This setting alone excludes null fields. `encodeDefaults` omits non-null defaults and
    // excludes no null, so it is deliberately not pinned here.
    assertThat(adkJson.configuration.explicitNulls).isFalse()
  }

  @Test
  fun theWalk_reportsANullSchemaFieldButNotANullInsideAMap() {
    // No endpoint fixture emits a null schema field - adkJson omits them - so the null half of the
    // rule is only falsifiable against a body written by hand.
    val walk = EmissionWalk()

    walk.visit(
      Json.parseToJsonElement(
        """{"id":null,"appName":"a","userId":"u","state":{"user_tier":null},"lastUpdateTime":1}"""
      ),
      SessionDto.serializer().descriptor,
      "SessionDto",
    )

    // The map entry is caller data in both halves: neither its null nor its underscore is reported.
    assertThat(walk.nullKeys).containsExactly("SessionDto.id")
    assertThat(walk.snakeCaseKeys).isEmpty()
  }

  @Test
  fun theWalk_acceptsAnInlineValueClassWrittenAsItsWrappedValue() {
    // ToolType is a value class, so a real response writes "toolType":"agent" rather than an
    // object. Treating that as a shape mismatch would fail the guard on legitimate traffic.
    val walk = EmissionWalk()

    walk.visit(
      Json.parseToJsonElement("""{"toolCall":{"id":"1","toolType":"agent"}}"""),
      Part.serializer().descriptor,
      "Part",
    )

    assertThat(walk.shapeMismatches).isEmpty()
    assertThat(walk.snakeCaseKeys).isEmpty()
  }

  @Test
  fun theWalk_reportsASubtreeItCouldNotEnter() {
    // Nothing else exercises this: every real fixture matches its descriptor, so without a
    // hand-written mismatch the walk could go back to skipping silently and stay green.
    val walk = EmissionWalk()

    walk.visit(
      Json.parseToJsonElement("""[{"id":"1","author":"a","content":[{"bad_key":null}]}]"""),
      ListSerializer(Event.serializer()).descriptor,
      "Event",
    )

    assertThat(walk.shapeMismatches).containsExactly("Event[].content")
  }

  private fun surveyAllRoots(): KeySurvey {
    // One survey per root, so a path is relative to the root it is reported under.
    val merged = KeySurvey()
    for ((name, serializer) in responseRoots) {
      val survey = KeySurvey()
      survey.visit(serializer.descriptor, name)
      merged.snakeCase += survey.snakeCase
      merged.visited += survey.visited
      merged.declined += survey.declined
      merged.declinedKinds += survey.declinedKinds
    }
    return merged
  }

  /**
   * Walks a descriptor graph and records every schema key, so a snake_case one is caught even on a
   * field no response happens to populate.
   */
  private class KeySurvey {
    val snakeCase = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val declined = mutableSetOf<String>()
    /** Kinds of the declined types, which survive a kotlinx rename of their serial names. */
    val declinedKinds = mutableSetOf<SerialKind>()
    // Keyed on identity: two instantiations of one generic share a serial name.
    private val seen = Collections.newSetFromMap(IdentityHashMap<SerialDescriptor, Boolean>())

    fun visit(descriptor: SerialDescriptor, path: String) {
      if (!seen.add(descriptor)) return
      // An inline value class contributes its wrapped type's keys, not a nesting level.
      if (descriptor.isInline) return visit(descriptor.getElementDescriptor(0), path)
      when (descriptor.kind) {
        StructureKind.LIST -> visit(descriptor.getElementDescriptor(0), "$path[]")
        // A map's keys are caller data, but its values may still be a schema type of ours.
        StructureKind.MAP -> visit(descriptor.getElementDescriptor(1), "$path{}")
        // A sealed hierarchy's variants are ours; element 1 holds them.
        PolymorphicKind.SEALED -> {
          val variants = descriptor.getElementDescriptor(1)
          for (i in 0 until variants.elementsCount) visit(variants.getElementDescriptor(i), path)
        }
        SerialKind.CONTEXTUAL,
        PolymorphicKind.OPEN -> {
          declined.add(descriptor.plainName)
          declinedKinds.add(descriptor.kind)
        }
        StructureKind.CLASS,
        StructureKind.OBJECT ->
          for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = "$path.$name"
            visited.add(child)
            if (name.contains('_')) snakeCase.add(child)
            visit(descriptor.getElementDescriptor(i), child)
          }
        else -> {}
      }
    }
  }

  private companion object {
    /** The serial name without kotlinx's nullability marker, so `X` and `X?` report as one type. */
    val SerialDescriptor.plainName: String
      get() = serialName.removeSuffix("?")
  }
}
