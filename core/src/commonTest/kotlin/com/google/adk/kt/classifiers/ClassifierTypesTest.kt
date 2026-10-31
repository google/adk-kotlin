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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalClassifierApi::class)

package com.google.adk.kt.classifiers

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

class ClassifierTypesTest {

  @Test
  fun questions_mutableJsonContent_isCopiedAndFrozen() {
    val nestedList = mutableListOf("ticket")
    val instructionsMap = mutableMapOf<String, Any?>("task" to "route", "context" to nestedList)
    val criteriaMap =
      mutableMapOf<String, Any?>(
        "billing" to mutableMapOf("examples" to mutableListOf("invoice")),
        "technical" to null,
      )

    val choice = Choice(instructions = instructionsMap, criteria = criteriaMap)

    nestedList.add("mutated")
    instructionsMap["task"] = "mutated"
    criteriaMap["injected"] = "evil"

    assertEquals(mapOf("task" to "route", "context" to listOf("ticket")), choice.instructions)
    assertEquals(
      mapOf("billing" to mapOf("examples" to listOf("invoice")), "technical" to null),
      choice.criteria,
    )
    assertFailsWith<UnsupportedOperationException> { (choice.criteria as MutableMap<*, *>).clear() }
  }

  @Test
  fun questions_jsonContent_isStoredInCanonicalForm() {
    val fromKotlinValues =
      Binary(
        instructions = mapOf("n" to 1, "s" to 2.toShort(), "f" to 0.1f, "flags" to listOf(true)),
        criteria = JsonPrimitive("text"),
      )
    val fromJsonElements =
      Choice(
        instructions =
          JsonObject(
            mapOf(
              "k" to JsonPrimitive("v"),
              "n" to JsonPrimitive(2),
              "x" to JsonPrimitive(2.5),
              "none" to JsonNull,
            )
          ),
        criteria = mapOf("a" to JsonArray(listOf(JsonPrimitive(1))), "b" to JsonPrimitive("str")),
      )

    assertEquals(
      mapOf("n" to 1L, "s" to 2L, "f" to 0.1, "flags" to listOf(true)),
      fromKotlinValues.instructions,
    )
    assertEquals("text", fromKotlinValues.criteria)
    assertEquals(
      mapOf("k" to "v", "n" to 2L, "x" to 2.5, "none" to null),
      fromJsonElements.instructions,
    )
    assertEquals(mapOf("a" to listOf(1L), "b" to "str"), fromJsonElements.criteria)
  }

  @Test
  fun questions_jsonNullInOptionalSlots_readsAsAbsent() {
    val choice = Choice(instructions = JsonNull, criteria = mapOf("a" to JsonNull, "b" to "B"))
    val binary = Binary(instructions = JsonNull, criteria = JsonNull)

    assertNull(choice.instructions)
    assertEquals(mapOf("a" to null, "b" to "B"), choice.criteria)
    assertEquals(Binary(), binary)
  }

  @Test
  fun questionValidation_invalidInputs_throwsIllegalArgumentException() {
    val validCriteria = mapOf("a" to null, "b" to null)
    val thoughtContent =
      Content(role = Role.MODEL, parts = listOf(Part(text = "secret", thought = true)))

    assertFailsWith<IllegalArgumentException> { Choice(criteria = emptyMap()) }
    assertFailsWith<IllegalArgumentException> { Choice(criteria = mapOf("only" to null)) }
    assertFailsWith<IllegalArgumentException> { Score(criteria = emptyList()) }
    assertFailsWith<IllegalArgumentException> { Score(criteria = listOf("only")) }
    assertFailsWith<IllegalArgumentException> { Score(criteria = listOf("low", JsonNull)) }

    val invalidJsonContents =
      listOf<Any>(
        42,
        true,
        3.14,
        byteArrayOf(1, 2, 3),
        setOf("a", "b"),
        mapOf("tags" to setOf(1, 2)),
        mapOf(1 to "non-string-key"),
        mapOf("nan" to Double.NaN),
        mapOf("inf" to Double.POSITIVE_INFINITY),
        listOf(Float.NaN),
        listOf(Float.NEGATIVE_INFINITY),
        thoughtContent,
        Part(text = "secret", thought = true),
        Blob(data = byteArrayOf(1), mimeType = "image/png"),
        mapOf("history" to listOf(thoughtContent)),
        JsonPrimitive(true),
        JsonPrimitive(42),
        JsonObject(mapOf("bad" to JsonUnquotedLiteral("NaN"))),
        JsonArray(listOf(JsonUnquotedLiteral("12345678901234567890"))),
        JsonArray(listOf(JsonUnquotedLiteral("True"))),
      )

    for (bad in invalidJsonContents) {
      val choiceInstrErr =
        assertFailsWith<IllegalArgumentException> {
          Choice(instructions = bad, criteria = validCriteria)
        }
      assertFalse(choiceInstrErr.message.orEmpty().contains("secret"))
      assertFailsWith<IllegalArgumentException> {
        Choice(criteria = mapOf("a" to bad, "b" to null))
      }
      assertFailsWith<IllegalArgumentException> { Binary(instructions = bad) }
      assertFailsWith<IllegalArgumentException> { Binary(criteria = bad) }
      assertFailsWith<IllegalArgumentException> {
        Score(instructions = bad, criteria = listOf("a", "b"))
      }
      assertFailsWith<IllegalArgumentException> { Score(criteria = listOf("low", bad)) }
    }
  }

  @Test
  fun questionValidation_nestingAtLimit_acceptedAndOverLimit_throws() {
    var atLimit: Any = "leaf"
    repeat(MAX_JSON_CONTENT_DEPTH) { atLimit = listOf(atLimit) }

    assertEquals(atLimit, Binary(instructions = atLimit).instructions)
    assertFailsWith<IllegalArgumentException> { Binary(instructions = listOf(atLimit)) }
    var emptyInnermost: Any = emptyList<Any>()
    repeat(MAX_JSON_CONTENT_DEPTH) { emptyInnermost = listOf(emptyInnermost) }
    assertFailsWith<IllegalArgumentException> { Binary(instructions = emptyInnermost) }
  }

  @Test
  fun answers_boundaryProbabilityAndConfidence_areAccepted() {
    assertEquals(0.0, BinaryAnswer(probability = 0.0, confidence = 0.0).probability)
    assertEquals(1.0, BinaryAnswer(probability = 1.0, confidence = 1.0).confidence)
    assertEquals(
      mapOf("a" to 1.0, "b" to 0.0),
      ChoiceAnswer(choice = "a", probabilities = mapOf("a" to 1.0, "b" to 0.0), confidence = 1.0)
        .probabilities,
    )
    assertEquals(
      mapOf(0 to 0.0, 1 to 1.0),
      ScoreAnswer(score = 1.0, probabilities = mapOf(0 to 0.0, 1 to 1.0), confidence = 0.0)
        .probabilities,
    )
  }

  @Test
  fun answers_probabilityOrConfidenceOutOfRange_throwsIllegalArgumentException() {
    for (badProb in
      listOf(-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> { BinaryAnswer(probability = badProb) }
      assertFailsWith<IllegalArgumentException> {
        BinaryAnswer(probability = 0.5, confidence = badProb)
      }
      assertFailsWith<IllegalArgumentException> {
        ChoiceAnswer(choice = "a", probabilities = mapOf("a" to badProb))
      }
      assertFailsWith<IllegalArgumentException> {
        ChoiceAnswer(choice = "a", probabilities = mapOf("a" to 1.0), confidence = badProb)
      }
      assertFailsWith<IllegalArgumentException> {
        ScoreAnswer(score = 0.0, probabilities = mapOf(0 to badProb))
      }
      assertFailsWith<IllegalArgumentException> {
        ScoreAnswer(score = 0.0, probabilities = mapOf(0 to 1.0), confidence = badProb)
      }
    }
    for (badScore in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> {
        ScoreAnswer(score = badScore, probabilities = mapOf(0 to 1.0))
      }
    }
  }

  @Test
  fun answersAndResponse_mutableInputs_areCopied() {
    val mutableProbs = mutableMapOf("a" to 0.8, "b" to 0.2)
    val choiceAns = ChoiceAnswer(choice = "a", probabilities = mutableProbs)
    mutableProbs["a"] = 0.0
    assertEquals(0.8, choiceAns.probabilities["a"])

    val mutableScoreProbs = mutableMapOf(0 to 0.25, 1 to 0.75)
    val scoreAns = ScoreAnswer(score = 0.75, probabilities = mutableScoreProbs)
    mutableScoreProbs[1] = 0.0
    assertEquals(0.75, scoreAns.probabilities[1])

    val mutableAnswers = mutableMapOf<String, Answer>("q" to RefusalAnswer)
    val response = ClassifierResponse(answers = mutableAnswers)
    mutableAnswers.clear()
    assertEquals(mapOf<String, Answer>("q" to RefusalAnswer), response.answers)
  }

  @Test
  fun equalsAndHashCode_signedZeroAndCopy_areConsistent() {
    val posZero = BinaryAnswer(probability = 0.0)
    val negZero = BinaryAnswer(probability = -0.0)
    assertNotEquals(posZero, negZero)
    assertEquals(posZero, posZero.copy())
    assertEquals(posZero.hashCode(), posZero.copy().hashCode())

    val scorePosZero = ScoreAnswer(score = 0.0, probabilities = mapOf(0 to 1.0))
    val scoreNegZero = ScoreAnswer(score = -0.0, probabilities = mapOf(0 to 1.0))
    assertNotEquals(scorePosZero, scoreNegZero)
    assertNotEquals(
      ChoiceAnswer(choice = "a", probabilities = mapOf("a" to 1.0), confidence = 0.0),
      ChoiceAnswer(choice = "a", probabilities = mapOf("a" to 1.0), confidence = -0.0),
    )
    assertEquals(scorePosZero, scorePosZero.copy())
    assertEquals(scorePosZero.hashCode(), scorePosZero.copy().hashCode())
  }

  @Test
  fun buildersAndToBuilder_nonDefaultValues_matchCopy() {
    val choice =
      Choice(
        instructions = mapOf("task" to "triage"),
        criteria = mapOf("billing" to "Invoices", "technical" to null),
      )
    assertEquals(choice.copy(), choice.toBuilder().build())
    assertEquals(
      Choice(
        instructions = "Pick one",
        criteria = mapOf("billing" to "Invoices", "technical" to null),
      ),
      Choice.builder()
        .instructions("Pick one")
        .criterion("billing", "Invoices")
        .criterion("technical")
        .build(),
    )

    val binary = Binary(instructions = "Deletes data?", criteria = "Deletes user records.")
    assertEquals(binary.copy(), binary.toBuilder().build())

    val score = Score(instructions = "Urgency?", criteria = listOf("low", "medium", "high"))
    assertEquals(score.copy(), score.toBuilder().build())

    val choiceAnswer =
      ChoiceAnswer(
        choice = "billing",
        probabilities = mapOf("billing" to 0.9, "technical" to 0.1),
        confidence = 0.85,
      )
    assertEquals(choiceAnswer.copy(), choiceAnswer.toBuilder().build())

    val binaryAnswer = BinaryAnswer(probability = 0.75, confidence = 0.9)
    assertEquals(binaryAnswer.copy(), binaryAnswer.toBuilder().build())

    val scoreAnswer =
      ScoreAnswer(
        score = 1.25,
        probabilities = mapOf(0 to 0.25, 1 to 0.25, 2 to 0.5),
        confidence = 0.8,
      )
    assertEquals(scoreAnswer.copy(), scoreAnswer.toBuilder().build())

    val response =
      ClassifierResponse(
        answers =
          mapOf(
            "route" to choiceAnswer,
            "risky" to binaryAnswer,
            "urgency" to scoreAnswer,
            "declined" to RefusalAnswer,
          ),
        modelVersion = "jev-1.13.0",
      )
    assertEquals(response.copy(), response.toBuilder().build())
  }

  @Test
  fun builders_missingRequiredProperty_throwsIllegalStateException() {
    assertFailsWith<IllegalStateException> { Choice.builder().build() }
    assertFailsWith<IllegalStateException> { Score.builder().build() }
    assertFailsWith<IllegalStateException> { ChoiceAnswer.builder().choice("a").build() }
    assertFailsWith<IllegalStateException> {
      ChoiceAnswer.builder().probabilities(mapOf("a" to 1.0)).build()
    }
    assertFailsWith<IllegalStateException> { BinaryAnswer.builder().build() }
    assertFailsWith<IllegalStateException> { ScoreAnswer.builder().score(0.0).build() }
    assertFailsWith<IllegalStateException> {
      ScoreAnswer.builder().probabilities(mapOf(0 to 1.0)).build()
    }
    assertFailsWith<IllegalStateException> { ClassifierResponse.builder().build() }
  }
}
