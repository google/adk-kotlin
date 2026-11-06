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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class ClassifierSerializersTest {

  private val codecs = listOf(Json.Default, adkJson)

  private val choice =
    Choice(
      instructions = "Which team?",
      criteria = mapOf("billing" to "Invoices", "technical" to null),
    )
  private val binary = Binary(criteria = "Deletes data.")
  private val score =
    Score(
      instructions = mapOf("scale" to 2, "strict" to true, "weight" to 0.5f),
      criteria = listOf("low", mapOf("level" to "high", "note" to null)),
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
  fun encode_writesPythonCompatibleWireFormat() {
    assertEquals(
      """{"type":"choice","instructions":"Which team?","criteria":{"billing":"Invoices","technical":null}}""",
      Json.encodeToString(Question.serializer(), choice),
    )
    assertEquals(
      """{"type":"binary","criteria":"Deletes data."}""",
      Json.encodeToString(Binary.serializer(), binary),
    )
    assertEquals(
      """{"type":"score","instructions":{"scale":2,"strict":true,"weight":0.5},"criteria":["low",{"level":"high","note":null}]}""",
      Json.encodeToString(Score.serializer(), score),
    )
    assertEquals(
      """{"answers":{"route":{"type":"choice","choice":"a","probabilities":{"a":0.9,"b":0.1}},""" +
        """"risky":{"type":"binary","probability":0.2,"confidence":0.9},""" +
        """"urgency":{"type":"score","score":0.75,"probabilities":{"0":0.25,"1":0.75},"confidence":0.5},""" +
        """"refused":{"type":"refusal"}},"model_version":"jev-1.13.0"}""",
      Json.encodeToString(
        ClassifierResponse.serializer(),
        ClassifierResponse(answers = answers, modelVersion = "jev-1.13.0"),
      ),
    )
  }

  @Test
  fun roundTrip_throughPolymorphicSerializers_preservesEquality() {
    val questionsSerializer = MapSerializer(String.serializer(), Question.serializer())
    val answersSerializer = MapSerializer(String.serializer(), Answer.serializer())
    val response = ClassifierResponse(answers = answers, modelVersion = "jev-1.13.0")

    for (codec in codecs) {
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
    for (codec in codecs) {
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
    }
  }

  @Test
  fun decode_explicitNullsAndMissingType_readAsAbsentAndDefault() {
    assertEquals(
      Choice(criteria = mapOf("a" to null, "b" to null)),
      Json.decodeFromString(
        Choice.serializer(),
        """{"instructions":null,"criteria":{"a":null,"b":null}}""",
      ),
    )
    assertEquals(
      BinaryAnswer(probability = 0.4),
      Json.decodeFromString(BinaryAnswer.serializer(), """{"probability":0.4,"confidence":null}"""),
    )
    assertEquals(
      ClassifierResponse(answers = emptyMap()),
      Json.decodeFromString(
        ClassifierResponse.serializer(),
        """{"answers":{},"model_version":null}""",
      ),
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

  private fun nestedLists(depth: Int): Any {
    var nested: Any = "leaf"
    repeat(depth) { nested = listOf(nested) }
    return nested
  }

  @Test
  fun decode_invalidQuestions_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"
    val invalidQuestions =
      listOf(
        """{"type":"choice","instruction":"$secret","criteria":{"a":null,"b":null}}""",
        """{"type":"choice","criteria":{"$secret":null}}""",
        """{"type":"choice","criteria":null}""",
        """{"type":"choice","instructions":true,"criteria":{"a":null,"b":null}}""",
        """{"type":"binary","criteria":123}""",
        """{"type":"binary","$secret":1}""",
        """{"type":"score","criteria":["low"]}""",
        """{"type":"score","criteria":["low",null]}""",
        """{"type":"score","criteria":["low",{"n":12345678901234567890}]}""",
        """{"type":"$secret"}""",
        """{"criteria":{"a":null,"b":null}}""",
        """[]""",
      )
    for ((index, raw) in invalidQuestions.withIndex()) {
      val err =
        assertFailsWith<SerializationException>("question #$index") {
          Json.decodeFromString(Question.serializer(), raw)
        }
      assertFalse(err.message.orEmpty().contains(secret), "question #$index")
    }
  }

  @Test
  fun decode_malformedJson_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"

    val err =
      assertFailsWith<SerializationException> {
        Json.decodeFromString(Binary.serializer(), """{"type":"binary","criteria":"$secret""")
      }

    assertFalse(err.message.orEmpty().contains(secret))
  }

  @Test
  fun decode_concreteSerializerWithOtherType_throwsSerializationException() {
    val mismatchedTypes =
      listOf(
        Choice.serializer() to """{"type":"binary","criteria":{"a":null,"b":null}}""",
        Choice.serializer() to """{"type":null,"criteria":{"a":null,"b":null}}""",
        Binary.serializer() to """{"type":"score"}""",
        Score.serializer() to """{"type":null,"criteria":["a","b"]}""",
      )
    for ((index, case) in mismatchedTypes.withIndex()) {
      val (serializer, raw) = case
      assertFailsWith<SerializationException>("mismatched #$index") {
        Json.decodeFromString(serializer, raw)
      }
    }
    assertFailsWith<SerializationException> {
      Json.decodeFromString(RefusalAnswer.serializer(), """{"type":"binary"}""")
    }
  }

  @Test
  fun decode_invalidAnswers_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"
    val invalidAnswers =
      listOf(
        """{"type":"refusal","extra":"$secret"}""",
        """{"type":"choice","choice":"a","probabilities":{"a":"0.5"}}""",
        """{"type":"choice","choice":"a","probabilities":{"a":1.5}}""",
        """{"type":"choice","probabilities":{"a":1.0}}""",
        """{"type":"binary","probability":-0.1}""",
        """{"type":"binary","probability":0.5f}""",
        """{"type":"binary","probability":0.5,"$secret":1}""",
        """{"type":"score","score":0.0,"probabilities":{"01":1.0}}""",
        """{"type":"score","score":0.0,"probabilities":{"+1":1.0}}""",
        """{"type":"score","score":0.0,"probabilities":{"999999999999":1.0}}""",
        """{"type":"$secret"}""",
        """{"probability":0.5}""",
      )
    for ((index, raw) in invalidAnswers.withIndex()) {
      val err =
        assertFailsWith<SerializationException>("answer #$index") {
          Json.decodeFromString(Answer.serializer(), raw)
        }
      assertFalse(err.message.orEmpty().contains(secret), "answer #$index")
    }
  }

  @Test
  fun decode_invalidResponses_throwsSerializationExceptionWithoutLeakingPayload() {
    val secret = "secret-untrusted-token-xyz"
    val invalidResponses =
      listOf(
        """{"answers":{"$secret":{"type":"choice","choice":"a","probabilities":{"$secret":null}}}}""",
        """{"answers":{"q":{"type":"binary","probability":-0.1}}}""",
        """{"answers":{"q":{"type":"$secret"}}}""",
        """{"answers":{"$secret":"not-an-object"}}""",
        """{"answers":{},"modelVersion":"$secret"}""",
        """{"answers":{},"model_version":123}""",
        """{"model_version":"m"}""",
      )
    for ((index, raw) in invalidResponses.withIndex()) {
      val err =
        assertFailsWith<SerializationException>("response #$index") {
          Json.decodeFromString(ClassifierResponse.serializer(), raw)
        }
      assertFalse(err.message.orEmpty().contains(secret), "response #$index")
    }
  }
}
