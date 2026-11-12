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
import com.google.adk.kt.webserver.models.AppInfo
import com.google.adk.kt.webserver.models.SessionDto
import com.google.adk.kt.webserver.models.SseError
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Holds the emission rule to the response types themselves, so a snake_case key is caught on a
 * field no response happens to populate. The rule covers schema keys, not a free-form map's keys,
 * so every check is driven by a [kotlinx.serialization.descriptors.SerialDescriptor] rather than a
 * scan of response text. [WireEndpointTest] applies the same rule to real responses.
 */
@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class WireEmissionCoverageTest {

  /**
   * The types the ADK agent runtime endpoints emit: `/version`, the session routes, the artifact
   * routes, `/run` and `/run_sse`, and `app-info`. `/health` and `/list-apps` emit no schema of
   * their own.
   */
  private val responseRoots: Map<String, KSerializer<*>> =
    mapOf(
      "VersionInfo" to VersionInfo.serializer(),
      "SessionDto" to SessionDto.serializer(),
      "Part" to Part.serializer(),
      "Event" to ListSerializer(Event.serializer()),
      "SseError" to SseError.serializer(),
      "AppInfo" to AppInfo.serializer(),
    )

  @Test
  fun adkJson_configuration_omitsNullFields() {
    // This alone drops every null field; encodeDefaults drops only values equal to a default.
    assertThat(adkJson.configuration.explicitNulls).isFalse()
  }

  @Test
  fun emissionWalk_inlineValueClassAsWrappedValue_reportsNoMismatch() {
    // ToolType is a value class, so a real response writes "toolType":"agent", not an object.
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
  fun emissionWalk_nullSchemaFieldAndNullMapValue_reportsOnlyTheField() {
    // adkJson omits null fields, so only a hand-written body can falsify the null half of the rule.
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
  fun emissionWalk_wrongShapedSubtree_reportsMismatch() {
    // Every real fixture matches its descriptor, so only this body proves mismatches are reported.
    val walk = EmissionWalk()

    walk.visit(
      Json.parseToJsonElement("""[{"id":"1","author":"a","content":[{"bad_key":null}]}]"""),
      ListSerializer(Event.serializer()).descriptor,
      "Event",
    )

    assertThat(walk.shapeMismatches).containsExactly("Event[].content")
  }

  @Test
  fun responseTypes_schemaKeys_areCamelCaseOrKnownAliases() {
    assertThat(schemaKeys { '_' in it }).containsExactlyElementsIn(KNOWN_LEGACY_ALIASES.keys)
    assertThat(schemaKeys()).containsAtLeastElementsIn(KNOWN_LEGACY_ALIASES.values)
  }

  @Test
  fun survey_responseTypes_declinesOnlyFreeFormValues() {
    // A declined type is a blind spot, so what gets declined is pinned rather than trusted.
    assertThat(WireSchemaSurvey(responseRoots).declined)
      .containsExactly(
        ContextualSerializer(Any::class).descriptor,
        JsonElement.serializer().descriptor,
      )
  }

  @Test
  fun survey_responseTypes_reachesPinnedKeys() {
    assertThat(schemaKeys())
      .containsAtLeast(
        "VersionInfo.languageVersion",
        "Part.inlineData.mimeType",
        "Event[].content.parts[].functionCall.name",
        "Event[].actions.requestedToolConfirmations{}.hint",
        "Event[].content.parts[].functionCall.partialArgs[].value.value",
        "AppInfo.agents{}.subAgents",
        "AppInfo.agents{}.tools[].functionDeclarations[].parameters.properties{}.type",
      )
  }

  /** The path of every schema key the response graph reaches whose name passes [matching]. */
  private fun schemaKeys(matching: (String) -> Boolean = { true }): Set<String> =
    WireSchemaSurvey(responseRoots).types.flatMapTo(mutableSetOf()) { surveyed ->
      surveyed.type.elementNames.filter(matching).map { "${surveyed.path}.$it" }
    }
}
