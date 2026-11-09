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

@file:OptIn(ExperimentalClassifierApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.classifiers

import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClassifierSerializersTest {

  private val codecs = mapOf("Json.Default" to Json.Default, "adkJson" to adkJson)

  private val choice =
    Choice(
      instructions = "Which team?",
      criteria = mapOf("billing" to "Invoices", "technical" to null),
    )
  private val binary = Binary(criteria = "Deletes data.")
  private val score =
    Score(
      instructions =
        ClassifierContent.parse(
          """{"scale": 2, "strict": true, "enabled": false, "weight": 0.5}"""
        ),
      criteria =
        listOf(
          ClassifierContent.Text("low"),
          ClassifierContent.parse("""{"level": "high", "note": null}"""),
        ),
    )
  private val questions = mapOf("route" to choice, "risky" to binary, "urgency" to score)

  private val choiceAnswer =
    ChoiceAnswer(choice = "a", probabilities = mapOf("a" to 0.9, "b" to 0.1))
  private val binaryAnswer = BinaryAnswer(probability = 0.2, confidence = 0.9)
  private val scoreAnswer =
    ScoreAnswer(score = 0.75, probabilities = mapOf(0 to 0.25, 1 to 0.75), confidence = 0.5)
  private val answers =
    mapOf(
      "route" to choiceAnswer,
      "risky" to binaryAnswer,
      "urgency" to scoreAnswer,
      "refused" to RefusalAnswer,
    )

  @Test
  fun encode_writesPythonCompatibleWireFormatIncludingNulls() {
    assertEquals(
      """{"type":"choice","instructions":"Which team?",""" +
        """"criteria":{"billing":"Invoices","technical":null}}""",
      Json.encodeToString(Question.serializer(), choice),
    )
    assertEquals(
      """{"type":"binary","instructions":null,"criteria":"Deletes data."}""",
      Json.encodeToString(Binary.serializer(), binary),
    )
    assertEquals(
      """{"type":"score","instructions":{"scale":2,"strict":true,"enabled":false,"weight":0.5},""" +
        """"criteria":["low",{"level":"high","note":null}]}""",
      Json.encodeToString(Score.serializer(), score),
    )
    assertEquals(
      """{"answers":{"route":{"type":"choice","choice":"a",""" +
        """"probabilities":{"a":0.9,"b":0.1},"confidence":null},""" +
        """"risky":{"type":"binary","probability":0.2,"confidence":0.9},""" +
        """"urgency":{"type":"score","score":0.75,""" +
        """"probabilities":{"0":0.25,"1":0.75},"confidence":0.5},""" +
        """"refused":{"type":"refusal"}},"model_version":"jev-1.13.0"}""",
      Json.encodeToString(
        ClassifierResponse.serializer(),
        ClassifierResponse(answers = answers, modelVersion = "jev-1.13.0"),
      ),
    )
    assertEquals(
      """{"answers":{},"model_version":null}""",
      adkJson.encodeToString(ClassifierResponse.serializer(), ClassifierResponse(emptyMap())),
    )
  }

  @Test
  fun decode_unnormalizedNumbersInQuestionContent_equalNormalizedContent() {
    val wire = """{"type":"binary","instructions":{"count":1e2,"ratio":1.50,"zero":-0}}"""

    val decoded = Json.decodeFromString(Binary.serializer(), wire)

    assertEquals(
      Binary(
        instructions =
          ClassifierContent.JsonObject(
            buildJsonObject {
              put("count", 100.0)
              put("ratio", 1.5)
              put("zero", 0L)
            }
          )
      ),
      decoded,
    )
  }

  @Test
  fun roundTrip_throughPolymorphicSerializers_preservesEquality() {
    val questionsSerializer = MapSerializer(String.serializer(), Question.serializer())
    val answersSerializer = MapSerializer(String.serializer(), Answer.serializer())
    val response = ClassifierResponse(answers = answers, modelVersion = "jev-1.13.0")

    for (codec in codecs.values) {
      assertEquals(
        questions,
        codec.decodeFromString(
          questionsSerializer,
          codec.encodeToString(questionsSerializer, questions),
        ),
      )
      assertEquals(
        answers,
        codec.decodeFromString(answersSerializer, codec.encodeToString(answersSerializer, answers)),
      )
      assertEquals(
        response,
        codec.decodeFromString(
          ClassifierResponse.serializer(),
          codec.encodeToString(ClassifierResponse.serializer(), response),
        ),
      )
    }
  }

  @Test
  fun roundTrip_throughConcreteSerializers_preservesEquality() {
    for (codec in codecs.values) {
      assertEquals(
        choice,
        codec.decodeFromString(
          Choice.serializer(),
          codec.encodeToString(Choice.serializer(), choice),
        ),
      )
      assertEquals(
        binary,
        codec.decodeFromString(
          Binary.serializer(),
          codec.encodeToString(Binary.serializer(), binary),
        ),
      )
      assertEquals(
        score,
        codec.decodeFromString(Score.serializer(), codec.encodeToString(Score.serializer(), score)),
      )
      assertEquals(
        choiceAnswer,
        codec.decodeFromString(
          ChoiceAnswer.serializer(),
          codec.encodeToString(ChoiceAnswer.serializer(), choiceAnswer),
        ),
      )
      assertEquals(
        binaryAnswer,
        codec.decodeFromString(
          BinaryAnswer.serializer(),
          codec.encodeToString(BinaryAnswer.serializer(), binaryAnswer),
        ),
      )
      assertEquals(
        scoreAnswer,
        codec.decodeFromString(
          ScoreAnswer.serializer(),
          codec.encodeToString(ScoreAnswer.serializer(), scoreAnswer),
        ),
      )
      assertEquals(
        RefusalAnswer,
        codec.decodeFromString(
          RefusalAnswer.serializer(),
          codec.encodeToString(RefusalAnswer.serializer(), RefusalAnswer),
        ),
      )
      assertEquals(
        ClassifierResponse(emptyMap()),
        codec.decodeFromString(
          ClassifierResponse.serializer(),
          codec.encodeToString(ClassifierResponse.serializer(), ClassifierResponse(emptyMap())),
        ),
      )
    }
  }

  @Test
  fun decode_missingOptionalFieldsAndMissingType_readAsAbsentAndDefault() {
    assertEquals(
      Choice(criteria = mapOf("a" to null, "b" to null)),
      Json.decodeFromString(Choice.serializer(), """{"criteria":{"a":null,"b":null}}"""),
    )
    assertEquals(Binary(), Json.decodeFromString(Question.serializer(), """{"type":"binary"}"""))
    assertEquals(
      BinaryAnswer(probability = 0.4),
      Json.decodeFromString(BinaryAnswer.serializer(), """{"probability":0.4}"""),
    )
    assertEquals(
      ClassifierResponse(answers = emptyMap()),
      Json.decodeFromString(ClassifierResponse.serializer(), """{"answers":{}}"""),
    )
  }

  @Test
  fun roundTrip_nestingAtLimit_preservesEquality() {
    val atLimit = Binary(instructions = nestedLists(MAX_JSON_CONTENT_DEPTH))

    val wire = Json.encodeToString(Binary.serializer(), atLimit)

    assertEquals(atLimit, Json.decodeFromString(Binary.serializer(), wire))
  }

  @Test
  fun decode_nestingOverLimit_throwsSerializationException() {
    val wire =
      Json.encodeToString(
        Binary.serializer(),
        Binary(instructions = nestedLists(MAX_JSON_CONTENT_DEPTH)),
      )
    val overLimit = wire.replace("\"leaf\"", "[\"leaf\"]")

    assertFailsWith<SerializationException> {
      Json.decodeFromString(Binary.serializer(), overLimit)
    }
  }

  private fun nestedLists(depth: Int): ClassifierContent =
    ClassifierContent.parse("[".repeat(depth) + "\"leaf\"" + "]".repeat(depth))

  @Test
  fun decode_invalidQuestions_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"
    val invalidQuestions =
      mapOf(
        "choice unknown field" to
          """{"type":"choice","instruction":"$secret","criteria":{"a":null,"b":null}}""",
        "choice single label" to """{"type":"choice","criteria":{"$secret":null}}""",
        "choice null criteria" to """{"type":"choice","criteria":null}""",
        "choice boolean instructions" to
          """{"type":"choice","instructions":true,"criteria":{"a":null,"b":null}}""",
        "choice numeric criterion description" to
          """{"type":"choice","criteria":{"a":1,"b":null}}""",
        "binary numeric criteria" to """{"type":"binary","criteria":123}""",
        "binary unknown field" to """{"type":"binary","$secret":1}""",
        "score single criterion" to """{"type":"score","criteria":["low"]}""",
        "score null criterion" to """{"type":"score","criteria":["low",null]}""",
        "score integer overflowing Long" to
          """{"type":"score","criteria":["low",{"n":12345678901234567890,"s":"$secret"}]}""",
        "score non-finite number" to
          """{"type":"score","criteria":["low",{"x":1e400,"s":"$secret"}]}""",
        "unsupported question type" to """{"type":"$secret"}""",
        "missing question type" to """{"criteria":{"a":null,"b":null}}""",
        "non-object question root" to """[]""",
      )

    assertRejectedWithoutLeaking(Question.serializer(), secret, invalidQuestions)
  }

  @Test
  fun decode_payloadRejectedByConstructor_reportsTheConstructorMessage() {
    val err =
      assertFailsWith<SerializationException> {
        Json.decodeFromString(Question.serializer(), """{"type":"score","criteria":["low"]}""")
      }

    assertEquals("Score requires at least 2 criteria levels, got 1.", err.message)
  }

  @Test
  fun decode_malformedJson_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"

    for ((codecName, codec) in codecs) {
      val err =
        assertFailsWith<SerializationException>(codecName) {
          codec.decodeFromString(Binary.serializer(), """{"type":"binary","criteria":"$secret""")
        }
      assertFalse(secret in err.message.orEmpty(), codecName)
    }
  }

  @Test
  fun decode_concreteSerializerWithOtherType_throwsSerializationException() {
    for ((codecName, codec) in codecs) {
      assertFailsWith<SerializationException>("choice given binary ($codecName)") {
        codec.decodeFromString(
          Choice.serializer(),
          """{"type":"binary","criteria":{"a":null,"b":null}}""",
        )
      }
      assertFailsWith<SerializationException>("choice given null type ($codecName)") {
        codec.decodeFromString(
          Choice.serializer(),
          """{"type":null,"criteria":{"a":null,"b":null}}""",
        )
      }
      assertFailsWith<SerializationException>("binary given score ($codecName)") {
        codec.decodeFromString(Binary.serializer(), """{"type":"score"}""")
      }
      assertFailsWith<SerializationException>("score given null type ($codecName)") {
        codec.decodeFromString(Score.serializer(), """{"type":null,"criteria":["a","b"]}""")
      }
      assertFailsWith<SerializationException>("refusal given binary ($codecName)") {
        codec.decodeFromString(RefusalAnswer.serializer(), """{"type":"binary"}""")
      }
    }
  }

  @Test
  fun decode_invalidAnswers_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"
    val invalidAnswers =
      mapOf(
        "refusal unknown field" to """{"type":"refusal","extra":"$secret"}""",
        "choice non-object probabilities" to
          """{"type":"choice","choice":"a","probabilities":[]}""",
        "choice string probability" to
          """{"type":"choice","choice":"a","probabilities":{"a":"0.5"}}""",
        "choice probability above 1" to
          """{"type":"choice","choice":"a","probabilities":{"a":1.5}}""",
        "choice missing choice field" to """{"type":"choice","probabilities":{"a":1.0}}""",
        "binary negative probability" to """{"type":"binary","probability":-0.1}""",
        "binary float-suffixed probability" to """{"type":"binary","probability":0.5f}""",
        "binary non-finite probability" to """{"type":"binary","probability":1e400}""",
        "binary unknown field" to """{"type":"binary","probability":0.5,"$secret":1}""",
        "score non-object probabilities" to """{"type":"score","score":0.0,"probabilities":null}""",
        "score non-finite score" to """{"type":"score","score":1e400,"probabilities":{"0":1.0}}""",
        "score leading-zero level key" to
          """{"type":"score","score":0.0,"probabilities":{"01":1.0}}""",
        "score plus-prefixed level key" to
          """{"type":"score","score":0.0,"probabilities":{"+1":1.0}}""",
        "score level key overflowing Int" to
          """{"type":"score","score":0.0,"probabilities":{"999999999999":1.0}}""",
        "unsupported answer type" to """{"type":"$secret"}""",
        "missing answer type" to """{"probability":0.5}""",
      )

    assertRejectedWithoutLeaking(Answer.serializer(), secret, invalidAnswers)
  }

  @Test
  fun decode_invalidResponses_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"
    val invalidResponses =
      mapOf(
        "null choice probability" to
          ("""{"answers":{"$secret":{"type":"choice","choice":"a",""" +
            """"probabilities":{"$secret":null}}}}"""),
        "nested out-of-range probability" to
          """{"answers":{"q":{"type":"binary","probability":-0.1}}}""",
        "nested unsupported answer type" to """{"answers":{"q":{"type":"$secret"}}}""",
        "non-object answer entry" to """{"answers":{"$secret":"not-an-object"}}""",
        "camelCase modelVersion unknown field" to """{"answers":{},"modelVersion":"$secret"}""",
        "numeric model_version" to """{"answers":{},"model_version":123}""",
        "missing answers field" to """{"model_version":"m"}""",
      )

    assertRejectedWithoutLeaking(ClassifierResponse.serializer(), secret, invalidResponses)
  }

  private fun <T> assertRejectedWithoutLeaking(
    serializer: KSerializer<T>,
    secret: String,
    cases: Map<String, String>,
  ) {
    for ((caseName, raw) in cases) {
      for ((codecName, codec) in codecs) {
        val label = "$caseName ($codecName)"
        val err =
          assertFailsWith<SerializationException>(label) { codec.decodeFromString(serializer, raw) }
        assertFalse(secret in err.message.orEmpty(), label)
      }
    }
  }
}
