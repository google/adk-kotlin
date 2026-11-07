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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ClassifierTypesTest {

  private val rubric =
    ClassifierContent.parse("""{"covers": "Refunds and charges", "not_for": ["Shipping"]}""")

  @Test
  fun classifierContentJson_equalsHashCodeAndToString_followTheJsonValue() {
    val obj =
      ClassifierContent.JsonObject(
        buildJsonObject {
          put("ticket", "double charge")
          putJsonArray("tags") {
            add("billing")
            add(2)
            add(true)
            add(JsonNull)
          }
        }
      )
    val array =
      ClassifierContent.JsonArray(
        buildJsonArray {
          add("first")
          add(buildJsonObject { put("score", 0.5) })
        }
      )

    assertEquals(
      """{"ticket":"double charge","tags":["billing",2,true,null]}""",
      obj.json.toString(),
    )
    assertEquals(
      obj,
      ClassifierContent.parse(
        """{ "ticket": "double charge", "tags": ["billing", 2, true, null] }"""
      ),
    )
    assertEquals(obj.hashCode(), ClassifierContent.parse(obj.json.toString()).hashCode())
    assertEquals(array, ClassifierContent.parse("""["first", {"score": 0.5}]"""))
    assertEquals(array.hashCode(), ClassifierContent.parse(array.json.toString()).hashCode())
    assertEquals("""JsonArray(json=["first",{"score":0.5}])""", array.toString())
    assertNotEquals<ClassifierContent>(obj, array)
    assertNotEquals(ClassifierContent.parse("[1]"), ClassifierContent.parse("[2]"))
  }

  @Test
  fun classifierContentJson_integersAtLongBoundaries_acceptedAndBeyondThem_throw() {
    val bounds = "[9223372036854775807,-9223372036854775808]"

    assertEquals(
      bounds,
      (ClassifierContent.parse(bounds) as ClassifierContent.JsonArray).json.toString(),
    )
    assertFailsWith<IllegalArgumentException> { ClassifierContent.parse("[9223372036854775808]") }
    assertFailsWith<IllegalArgumentException> { ClassifierContent.parse("[-9223372036854775809]") }
  }

  @Test
  fun classifierContentJson_numbers_areWrittenAsTheLongOrDoubleTheyParseTo() {
    val parsed = ClassifierContent.parse("[1e2, 1.50, -0, 12345678901234567.5, -0.0, 7]")

    assertEquals(
      "[100.0,1.5,0,1.2345678901234568E16,-0.0,7]",
      (parsed as ClassifierContent.JsonArray).json.toString(),
    )
    assertEquals(
      ClassifierContent.JsonArray(
        buildJsonArray {
          add(100.0)
          add(1.5)
          add(0)
          add(1.2345678901234568E16)
          add(-0.0)
          add(7L)
        }
      ),
      parsed,
    )
  }

  @Test
  fun classifierContentJson_invalidTree_throwsWithoutQuotingIt() {
    val invalid =
      listOf(
        buildJsonArray { add(Double.NaN) },
        buildJsonArray { add(Float.POSITIVE_INFINITY) },
        // kotlinx reads unquoted words and out-of-range numbers as literals without checking them.
        Json.parseToJsonElement("[12345678901234567890]").jsonArray,
        Json.parseToJsonElement("[secret]").jsonArray,
        Json.parseToJsonElement("[1e400]").jsonArray,
      )

    for ((index, json) in invalid.withIndex()) {
      val error =
        assertFailsWith<IllegalArgumentException>("tree #$index") {
          ClassifierContent.JsonArray(json)
        }
      assertNull(error.cause, "tree #$index")
      for (payload in listOf("secret", "12345678901234567890", "1e400")) {
        assertFalse(payload in error.message.orEmpty(), "tree #$index")
      }
    }
    assertFailsWith<IllegalArgumentException> {
      ClassifierContent.JsonObject(buildJsonObject { put("nan", Double.NaN) })
    }
  }

  @Test
  fun classifierContentParse_textThatIsNotAJsonObjectOrArray_throwsWithoutQuotingIt() {
    val invalid =
      listOf(
        "\"secret\"",
        "1",
        "true",
        "null",
        "",
        """{"secret"""",
        """{"a": NaN}""",
        """{"a": secret}""",
        """["secret"] trailing""",
        """{"x": 1e400}""",
        """{"n": 12345678901234567890, "s": "secret"}""",
      )

    for ((index, json) in invalid.withIndex()) {
      val error =
        assertFailsWith<IllegalArgumentException>("json #$index") { ClassifierContent.parse(json) }
      assertNull(error.cause, "json #$index")
      for (payload in listOf("secret", "12345678901234567890", "1e400")) {
        assertFalse(payload in error.message.orEmpty(), "json #$index")
      }
    }
  }

  @Test
  fun classifierContentJson_nestingAtLimit_acceptedAndOverLimit_throws() {
    val atLimit = "[".repeat(MAX_JSON_CONTENT_DEPTH) + "]".repeat(MAX_JSON_CONTENT_DEPTH)
    val parsed = ClassifierContent.parse(atLimit) as ClassifierContent.JsonArray

    assertEquals(atLimit, parsed.json.toString())
    assertFailsWith<IllegalArgumentException> { ClassifierContent.parse("[$atLimit]") }
    assertFailsWith<IllegalArgumentException> {
      ClassifierContent.JsonArray(buildJsonArray { add(parsed.json) })
    }
    assertFailsWith<IllegalArgumentException> {
      ClassifierContent.JsonObject(buildJsonObject { put("k", parsed.json) })
    }
  }

  @Test
  fun classifierContentParse_deepArrays_throwIllegalArgumentException() {
    val deep = "[".repeat(100_000) + "]".repeat(100_000)

    assertFailsWith<IllegalArgumentException> { ClassifierContent.parse(deep) }
  }

  @Test
  fun classifierContentParse_bracketsInStrings_doNotCountTowardTheDepthLimit() {
    val bracketsInString = "[\"" + "[{".repeat(MAX_JSON_CONTENT_DEPTH) + "\\\"]\"]"

    assertEquals(
      1,
      (ClassifierContent.parse(bracketsInString) as ClassifierContent.JsonArray).json.size,
    )
  }

  @Test
  fun classifierContentAndInputOf_plainText_returnText() {
    assertEquals<ClassifierContent>(
      ClassifierContent.Text("ticket"),
      ClassifierContent.of("ticket"),
    )
    assertEquals<ClassifierInput>(ClassifierContent.Text("ticket"), ClassifierInput.of("ticket"))
  }

  @Test
  fun classifierRequest_convenienceConstructorsAndValidation_enforceNonEmptyQuestions() {
    val questions = mapOf("urgent" to Binary(instructions = "Urgent?"))
    val content = Content.fromText(Role.USER, "ticket")

    assertEquals(
      ClassifierRequest(ClassifierContent.Text("ticket"), questions),
      ClassifierRequest("ticket", questions),
    )
    assertEquals(
      ClassifierRequest(ClassifierInput.Content(content), questions),
      ClassifierRequest(content, questions),
    )

    assertFailsWith<IllegalArgumentException> { ClassifierRequest("ticket", emptyMap()) }
    @Suppress("UNCHECKED_CAST") val nullQuestion = mapOf("q" to null) as Map<String, Question>
    assertFailsWith<IllegalArgumentException> { ClassifierRequest("ticket", nullQuestion) }
  }

  @Test
  fun questions_textConstructors_matchClassifierContentConstructors() {
    assertEquals(
      Choice(
        instructions = ClassifierContent.Text("Route the ticket."),
        criteria = mapOf("billing" to ClassifierContent.Text("Invoices"), "technical" to null),
      ),
      Choice(
        instructions = "Route the ticket.",
        criteria = mapOf("billing" to "Invoices", "technical" to null),
      ),
    )
    assertEquals(
      Binary(
        instructions = ClassifierContent.Text("Deletes data?"),
        criteria = ClassifierContent.Text("Deletes user records."),
      ),
      Binary(instructions = "Deletes data?", criteria = "Deletes user records."),
    )
    assertEquals(Binary(instructions = ClassifierContent.Text("Urgent?")), Binary("Urgent?"))
    assertEquals(
      Binary(criteria = ClassifierContent.Text("Deletes data.")),
      Binary(criteria = "Deletes data."),
    )
    assertEquals(
      Score(
        instructions = ClassifierContent.Text("Urgency?"),
        criteria = listOf(ClassifierContent.Text("low"), ClassifierContent.Text("high")),
      ),
      Score(instructions = "Urgency?", criteria = listOf("low", "high")),
    )
  }

  @Test
  fun questions_nullOrLabelOnlyArguments_resolveToTheContentOverloads() {
    val labelsOnly = Choice(criteria = mapOf("calm" to null, "angry" to null))

    assertEquals(mapOf("calm" to null, "angry" to null), labelsOnly.criteria)
    assertEquals(listOf("calm", "angry"), labelsOnly.criteria.keys.toList())
    assertEquals(Binary(), Binary(null))
    assertEquals(Binary(), Binary(instructions = null, criteria = null))
  }

  @Test
  fun questions_structuredContent_preservePropertiesAndSupportDestructuring() {
    val choice =
      Choice(
        instructions = ClassifierContent.parse("""["Route the ticket.", "Prefer billing."]"""),
        criteria = mapOf("billing" to rubric, "technical" to ClassifierContent.Text("Bugs")),
      )

    val (instructions, criteria) = choice

    assertEquals(choice.instructions, instructions)
    assertEquals(rubric, criteria["billing"])
    assertNull(Binary().instructions)
    assertNull(Binary().criteria)
  }

  @Test
  fun choiceBuilder_criterionAfterBuild_leavesBuiltChoiceUnchanged() {
    val builder = Choice.builder().criterion("billing", "Invoices").criterion("technical")
    val built = builder.build()

    val unused = builder.criterion("extra", "Added later")

    assertFalse("extra" in built.criteria)
  }

  @Test
  fun questionValidation_tooFewLabelsOrLevels_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { Choice(criteria = emptyMap()) }
    assertFailsWith<IllegalArgumentException> { Choice(criteria = mapOf("only" to null)) }
    assertFailsWith<IllegalArgumentException> { Choice(criteria = mapOf("only" to rubric)) }
    assertFailsWith<IllegalArgumentException> {
      Choice(instructions = "Pick one", criteria = mapOf("only" to "x"))
    }
    assertFailsWith<IllegalArgumentException> { Score(criteria = emptyList()) }
    assertFailsWith<IllegalArgumentException> { Score(criteria = listOf(rubric)) }
    assertFailsWith<IllegalArgumentException> {
      Score(instructions = null, criteria = listOf("only"))
    }
  }

  @Test
  fun questionValidation_valuesThatBypassKotlinTypes_throwsIllegalArgumentException() {
    @Suppress("UNCHECKED_CAST") val intLabels = mapOf(1 to "a", 2 to "b") as Map<String, String?>
    @Suppress("UNCHECKED_CAST")
    val intDescriptions = mapOf("a" to 1, "b" to null) as Map<String, String?>
    @Suppress("UNCHECKED_CAST")
    val stringContents = mapOf("a" to "x", "b" to null) as Map<String, ClassifierContent?>
    @Suppress("UNCHECKED_CAST") val nullLevel = listOf("low", null) as List<String>
    @Suppress("UNCHECKED_CAST") val intLevel = listOf("low", 2) as List<String>
    @Suppress("UNCHECKED_CAST")
    val stringContentLevel =
      listOf(ClassifierContent.Text("low"), "high") as List<ClassifierContent>
    @Suppress("UNCHECKED_CAST")
    val nullContentLevel = listOf(ClassifierContent.Text("low"), null) as List<ClassifierContent>

    assertFailsWith<IllegalArgumentException> { Choice(instructions = null, criteria = intLabels) }
    assertFailsWith<IllegalArgumentException> {
      Choice(instructions = null, criteria = intDescriptions)
    }
    assertFailsWith<IllegalArgumentException> { Choice(criteria = stringContents) }
    assertFailsWith<IllegalArgumentException> { Score(instructions = null, criteria = nullLevel) }
    assertFailsWith<IllegalArgumentException> { Score(instructions = null, criteria = intLevel) }
    assertFailsWith<IllegalArgumentException> { Score(criteria = stringContentLevel) }
    assertFailsWith<IllegalArgumentException> { Score(criteria = nullContentLevel) }
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
      val case = "probability $badProb"
      assertFailsWith<IllegalArgumentException>(case) { BinaryAnswer(probability = badProb) }
      assertFailsWith<IllegalArgumentException>(case) {
        BinaryAnswer(probability = 0.5, confidence = badProb)
      }
      assertFailsWith<IllegalArgumentException>(case) {
        ChoiceAnswer(choice = "a", probabilities = mapOf("a" to badProb))
      }
      assertFailsWith<IllegalArgumentException>(case) {
        ChoiceAnswer(choice = "a", probabilities = mapOf("a" to 1.0), confidence = badProb)
      }
      assertFailsWith<IllegalArgumentException>(case) {
        ScoreAnswer(score = 0.0, probabilities = mapOf(0 to badProb))
      }
      assertFailsWith<IllegalArgumentException>(case) {
        ScoreAnswer(score = 0.0, probabilities = mapOf(0 to 1.0), confidence = badProb)
      }
    }
    for (badScore in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException>("score $badScore") {
        ScoreAnswer(score = badScore, probabilities = mapOf(0 to 1.0))
      }
    }
  }

  @Test
  fun buildersAndToBuilder_nonDefaultValues_matchCopy() {
    val choice =
      Choice(
        instructions = ClassifierContent.parse("""["Triage the ticket."]"""),
        criteria = mapOf("billing" to rubric, "technical" to null),
      )
    assertEquals(choice.copy(), choice.toBuilder().build())
    assertEquals(
      Choice(
        instructions = ClassifierContent.Text("Pick one"),
        criteria =
          mapOf(
            "billing" to ClassifierContent.Text("Invoices"),
            "returns" to rubric,
            "technical" to null,
          ),
      ),
      Choice.builder()
        .instructions("Pick one")
        .criterion("billing", "Invoices")
        .criterion("returns", rubric)
        .criterion("technical")
        .build(),
    )

    val binary = Binary(instructions = ClassifierContent.Text("Deletes data?"), criteria = rubric)
    assertEquals(binary.copy(), binary.toBuilder().build())
    assertEquals(
      Binary(instructions = "Deletes data?", criteria = "Deletes user records."),
      Binary.builder().instructions("Deletes data?").criteria("Deletes user records.").build(),
    )

    val score =
      Score(
        instructions = ClassifierContent.Text("Urgency?"),
        criteria = listOf(ClassifierContent.Text("low"), rubric),
      )
    assertEquals(score.copy(), score.toBuilder().build())
    assertEquals(
      Score(instructions = "Urgency?", criteria = listOf("low", "high")),
      Score.builder()
        .instructions("Urgency?")
        .criteria(listOf(ClassifierContent.Text("low"), ClassifierContent.Text("high")))
        .build(),
    )
    assertEquals(
      Score(instructions = "Urgency?", criteria = listOf("low", "high")),
      Score.builder().instructions("Urgency?").criteria("low", "high").build(),
    )

    val request =
      ClassifierRequest(
        input = ClassifierContent.parse("""{"ticket":"refund"}"""),
        questions = mapOf("route" to choice, "risky" to binary, "urgency" to score),
      )
    assertEquals(request.copy(), request.toBuilder().build())

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
    assertFailsWith<IllegalStateException> { ClassifierRequest.builder().build() }
    assertFailsWith<IllegalStateException> {
      ClassifierRequest.builder().input(ClassifierContent.Text("x")).build()
    }
    assertFailsWith<IllegalStateException> {
      ClassifierRequest.builder().questions(mapOf("q" to Binary())).build()
    }
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
