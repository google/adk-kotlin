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

import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.models.AgentRunRequest
import com.google.adk.kt.webserver.models.CreateSessionRequest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Holds the wire contract's intake rule to the types that carry it.
 *
 * The server reads `snake_case` because each multi-word property declares that spelling as an
 * alternative name. Nothing in the type system requires it, so this walks the request bodies the
 * routes decode and fails when a property is reachable only under its camelCase name.
 */
@RunWith(JUnit4::class)
class WireAliasCoverageTest {

  /**
   * The request bodies the routes decode: `/run` and `/run_sse` take the first, uploads the second,
   * and session creation the third.
   */
  private val wireRoots: Map<String, KSerializer<*>> =
    mapOf(
      "AgentRunRequest" to AgentRunRequest.serializer(),
      "Part" to Part.serializer(),
      "CreateSessionRequest" to CreateSessionRequest.serializer(),
    )

  @Test
  fun everyMultiWordWireProperty_declaresItsSnakeCaseSpelling() {
    assertThat(WireSchemaSurvey(wireRoots).missingAliases()).isEmpty()
  }

  @Test
  fun noTwoPropertiesInAClass_shareAnAlternativeName() {
    // kotlinx builds its alias map only for a key that misses every declared name, so a duplicate
    // can sit unreported until some request happens to carry one.
    for (surveyed in WireSchemaSurvey(wireRoots).types) {
      val type = surveyed.type
      val claimed =
        type.elementNames.flatMapIndexed { index, name -> type.alternatives(index) + name }
      assertWithMessage(surveyed.path).that(claimed).containsNoDuplicates()
    }
  }

  @Test
  fun theSurvey_entersEveryTypeThatSharesASerialName() {
    // Guards the survey itself, which is only useful if it reaches everything. Two lists share one
    // serial name, and so do two instantiations of one generic, so a survey keyed on the name
    // rather than the descriptor skips the second of each - a hole exactly where the real graph
    // happens to have none.
    val survey = WireSchemaSurvey(mapOf("SharedNames" to SharedNames.serializer()))

    assertThat(survey.missingAliases())
      .containsExactly(
        """SharedNames.beta[].inAList (needs @JsonNames("in_a_list"))""",
        """SharedNames.delta.held.inABox (needs @JsonNames("in_a_box"))""",
      )
  }

  @Test
  fun theSurvey_asksForTheAliasTheStrategyWouldDerive() {
    // An acronym is where a hand-rolled transform and the library's part company: a survey that
    // asked for file_u_r_i would have someone declare an alias no client ever sends.
    val survey = WireSchemaSurvey(mapOf("Acronyms" to Acronyms.serializer()))

    assertThat(survey.missingAliases())
      .containsExactly("""Acronyms.fileURI (needs @JsonNames("file_uri"))""")
  }

  @Test
  fun theSurvey_entersEveryVariantOfASealedHierarchy() {
    val survey = WireSchemaSurvey(mapOf("Variants" to Variants.serializer()))

    assertThat(survey.missingAliases())
      .containsExactly(
        """Variants.bareName (needs @JsonNames("bare_name"))""",
        """Variants.unaliasedName (needs @JsonNames("unaliased_name"))""",
      )
  }

  @Test
  fun typesTheSurveyDoesNotEnter_areTheKnownOnes() {
    // These carry caller data, so they name no fields to alias. JsonElement is reachable only
    // through EventActions, so this also pins that the survey gets there - the coverage assertion
    // above would pass vacuously if it stopped.
    assertThat(WireSchemaSurvey(wireRoots).declined)
      .containsExactly(
        ContextualSerializer(Any::class).descriptor,
        JsonElement.serializer().descriptor,
      )
  }

  /** Each multi-word property the survey reached without its snake_case alternative name. */
  private fun WireSchemaSurvey.missingAliases(): Set<String> =
    types.flatMapTo(mutableSetOf()) { surveyed ->
      val type = surveyed.type
      type.elementNames.mapIndexedNotNull { index, name ->
        // Derived from the declared name, so a @SerialName override decides the expected spelling.
        val snakeCase = JsonNamingStrategy.SnakeCase.serialNameForJson(type, index, name)
        if (snakeCase == name || snakeCase in type.alternatives(index)) null
        else """${surveyed.path}.$name (needs @JsonNames("$snakeCase"))"""
      }
    }

  private fun SerialDescriptor.alternatives(index: Int): List<String> =
    getElementAnnotations(index).filterIsInstance<JsonNames>().flatMap { it.names.asList() }
}

@Serializable private data class Aliased(@JsonNames("well_formed") val wellFormed: String? = null)

@Serializable private data class Behind(val inAList: String? = null)

@Serializable private data class Deeper(val inABox: String? = null)

@Serializable private data class Box<T>(val held: T)

/**
 * Pairs that share a serial name: two lists, and two instantiations of one generic. The properties
 * are single words so the fixture does not report itself, and the two unaliased types differ so
 * each path reports independently.
 */
@Serializable
private data class SharedNames(
  val alpha: List<Aliased> = emptyList(),
  val beta: List<Behind> = emptyList(),
  val gamma: Box<Aliased>,
  val delta: Box<Deeper>,
)

/** Consecutive capitals, where a naive transform and the naming strategy disagree. */
@Serializable private data class Acronyms(val fileURI: String? = null)

/** A sealed hierarchy with an aliased variant between two unaliased ones. */
@Serializable
private sealed interface Variants {
  @Serializable data class Bare(val bareName: String? = null) : Variants

  @Serializable data class Named(@JsonNames("well_named") val wellNamed: String? = null) : Variants

  @Serializable data class Unaliased(val unaliasedName: String? = null) : Variants
}
