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
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
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
@OptIn(ExperimentalSerializationApi::class)
@RunWith(JUnit4::class)
class WireAliasCoverageTest {

  /**
   * The request bodies the routes decode: `/run` and `/run_sse` take the first, uploads the second.
   */
  private val wireRoots: Map<String, KSerializer<*>> =
    mapOf("AgentRunRequest" to AgentRunRequest.serializer(), "Part" to Part.serializer())

  @Test
  fun everyMultiWordWireProperty_declaresItsSnakeCaseSpelling() {
    val survey = survey()

    assertThat(survey.missing).isEmpty()
  }

  @Test
  fun noTwoPropertiesInAClass_shareAnAlternativeName() {
    // kotlinx builds its alias map only for a key that misses every declared name, so a duplicate
    // can sit unreported until some request happens to carry one.
    assertThat(survey().duplicated).isEmpty()
  }

  @Test
  fun theSurvey_entersEveryTypeThatSharesASerialName() {
    // Guards the survey itself, which is only useful if it reaches everything. Two lists share one
    // serial name, and so do two instantiations of one generic, so a survey keyed on the name
    // rather than the descriptor skips the second of each - a hole exactly where the real graph
    // happens to have none.
    val survey = AliasSurvey()

    survey.visit(SharedNames.serializer().descriptor, "SharedNames")

    assertThat(survey.missing)
      .containsExactly(
        """SharedNames.beta[].inAList (needs @JsonNames("in_a_list"))""",
        """SharedNames.delta.held.inABox (needs @JsonNames("in_a_box"))""",
      )
  }

  @Test
  fun theSurvey_asksForTheAliasTheStrategyWouldDerive() {
    // An acronym is where a hand-rolled transform and the library's part company: a survey that
    // asked for file_u_r_i would have someone declare an alias no client ever sends.
    val survey = AliasSurvey()

    survey.visit(Acronyms.serializer().descriptor, "Acronyms")

    assertThat(survey.missing)
      .containsExactly("""Acronyms.fileURI (needs @JsonNames("file_uri"))""")
  }

  @Test
  fun typesTheSurveyDoesNotEnter_areTheKnownOnes() {
    // These carry caller data or dispatch polymorphically, so they name no fields to alias.
    assertThat(survey().notEntered)
      .containsExactly(
        "kotlinx.serialization.ContextualSerializer<Any>",
        "com.google.adk.kt.types.PartialArgValue",
      )
  }

  private fun survey(): AliasSurvey {
    val survey = AliasSurvey()
    for ((name, serializer) in wireRoots) survey.visit(serializer.descriptor, name)
    return survey
  }

  /** Collects properties missing an alias, duplicate aliases, and types it declines to enter. */
  private class AliasSurvey {
    val missing = mutableSetOf<String>()
    val duplicated = mutableSetOf<String>()
    val notEntered = mutableSetOf<String>()
    // Keyed on identity, not serial name: List<A> and List<B> share one name, as do Box<A> and
    // Box<B>, so a name-keyed set skips the second of each. A non-generic type is one descriptor
    // however often it is reached, so its cycles terminate; a recursive generic would not.
    private val seen = Collections.newSetFromMap(IdentityHashMap<SerialDescriptor, Boolean>())

    fun visit(descriptor: SerialDescriptor, path: String) {
      if (!seen.add(descriptor)) return
      when {
        descriptor.isInline -> return visit(descriptor.getElementDescriptor(0), path)
        descriptor.kind == SerialKind.CONTEXTUAL || descriptor.kind is PolymorphicKind -> {
          notEntered.add(descriptor.serialName.removeSuffix("?"))
          return
        }
        descriptor.kind == StructureKind.MAP ->
          return visit(descriptor.getElementDescriptor(1), "$path[]")
        descriptor.kind == StructureKind.LIST ->
          return visit(descriptor.getElementDescriptor(0), "$path[]")
        descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT -> return
      }
      val claimed = mutableMapOf<String, String>()
      for (index in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(index)
        val alternatives =
          descriptor.getElementAnnotations(index).filterIsInstance<JsonNames>().flatMap {
            it.names.asList()
          }
        // Derived from the declared name, so a @SerialName override decides the expected spelling.
        val snakeCase = JsonNamingStrategy.SnakeCase.serialNameForJson(descriptor, index, name)
        if (snakeCase != name && snakeCase !in alternatives) {
          missing.add("""$path.$name (needs @JsonNames("$snakeCase"))""")
        }
        for (alternative in alternatives + name) {
          claimed.put(alternative, name)?.let {
            duplicated.add("$path: $it and $name share '$alternative'")
          }
        }
        visit(descriptor.getElementDescriptor(index), "$path.$name")
      }
    }
  }
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
