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

@file:OptIn(ExperimentalClassifierApi::class)

package com.google.adk.kt.classifiers

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.serialization.anyToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

private const val TYPE_CHOICE = "choice"
private const val TYPE_BINARY = "binary"
private const val TYPE_SCORE = "score"
private const val TYPE_REFUSAL = "refusal"

private val QUESTION_KEYS = setOf("type", "instructions", "criteria")
private val CHOICE_ANSWER_KEYS = setOf("type", "choice", "probabilities", "confidence")
private val BINARY_ANSWER_KEYS = setOf("type", "probability", "confidence")
private val SCORE_ANSWER_KEYS = setOf("type", "score", "probabilities", "confidence")
private val REFUSAL_ANSWER_KEYS = setOf("type")
private val RESPONSE_KEYS = setOf("answers", "model_version")

/**
 * Base of the classifier serializers, which read and write one JSON object each and support JSON
 * formats only. [kind] names the object in error messages, which never quote the payload; decoding
 * rejects keys outside [allowedKeys] and a `type` other than [expectedType], but tolerates no
 * `type`.
 */
internal abstract class ClassifierJsonSerializer<T>(
  private val kind: String,
  private val allowedKeys: Set<String>? = null,
  private val expectedType: String? = null,
) : KSerializer<T> {
  final override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  final override fun serialize(encoder: Encoder, value: T) {
    val jsonEncoder =
      encoder as? JsonEncoder
        ?: throw SerializationException("Classifier types support JSON formats only.")
    jsonEncoder.encodeJsonElement(encode(value))
  }

  @Suppress("UnusedException") // Chaining the cause would quote untrusted input.
  final override fun deserialize(decoder: Decoder): T {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw SerializationException("Classifier types support JSON formats only.")
    val element =
      try {
        jsonDecoder.decodeJsonElement()
      } catch (e: IllegalArgumentException) {
        throw SerializationException("Malformed $kind JSON.")
      }
    return decode(
      element as? JsonObject ?: throw SerializationException("Expected $kind JSON to be an object.")
    )
  }

  abstract fun encode(value: T): JsonObject

  fun decode(obj: JsonObject): T {
    if (allowedKeys != null && obj.keys.any { it !in allowedKeys }) {
      throw SerializationException("Unknown field in $kind JSON.")
    }
    val type = obj["type"]
    if (expectedType != null && type != null && type.stringOrNull() != expectedType) {
      throw SerializationException("Expected '$expectedType' as the $kind type.")
    }
    return try {
      decodeFields(obj)
    } catch (e: SerializationException) {
      throw e
    } catch (e: IllegalArgumentException) {
      // The constructors' messages name only types and counts, never the payload.
      throw SerializationException(e.message ?: "Invalid $kind payload.", e)
    }
  }

  protected abstract fun decodeFields(obj: JsonObject): T
}

/** Serializes any [Question] as a JSON object whose `type` is `choice`, `binary` or `score`. */
internal object QuestionSerializer : ClassifierJsonSerializer<Question>("question") {
  override fun encode(value: Question): JsonObject =
    when (value) {
      is Choice -> ChoiceSerializer.encode(value)
      is Binary -> BinarySerializer.encode(value)
      is Score -> ScoreSerializer.encode(value)
    }

  override fun decodeFields(obj: JsonObject): Question =
    when (readRequiredString(obj, "type")) {
      TYPE_CHOICE -> ChoiceSerializer.decode(obj)
      TYPE_BINARY -> BinarySerializer.decode(obj)
      TYPE_SCORE -> ScoreSerializer.decode(obj)
      else -> throw SerializationException("Unsupported question type.")
    }
}

/** Serializes a [Choice]; the `criteria` object keeps `null` for labels without a description. */
internal object ChoiceSerializer :
  ClassifierJsonSerializer<Choice>("choice question", QUESTION_KEYS, TYPE_CHOICE) {
  override fun encode(value: Choice): JsonObject = buildJsonObject {
    put("type", TYPE_CHOICE)
    if (value.instructions != null) put("instructions", content(value.instructions))
    put("criteria", content(value.criteria))
  }

  override fun decodeFields(obj: JsonObject): Choice {
    val criteria =
      obj["criteria"] as? JsonObject
        ?: throw SerializationException("Choice question requires a criteria object.")
    return Choice(instructions = obj["instructions"], criteria = criteria)
  }
}

/** Serializes a [Binary], omitting absent `instructions` and `criteria`. */
internal object BinarySerializer :
  ClassifierJsonSerializer<Binary>("binary question", QUESTION_KEYS, TYPE_BINARY) {
  override fun encode(value: Binary): JsonObject = buildJsonObject {
    put("type", TYPE_BINARY)
    if (value.instructions != null) put("instructions", content(value.instructions))
    if (value.criteria != null) put("criteria", content(value.criteria))
  }

  override fun decodeFields(obj: JsonObject): Binary =
    Binary(instructions = obj["instructions"], criteria = obj["criteria"])
}

/** Serializes a [Score]; `criteria` is the array of rubric levels, starting at level 0. */
internal object ScoreSerializer :
  ClassifierJsonSerializer<Score>("score question", QUESTION_KEYS, TYPE_SCORE) {
  override fun encode(value: Score): JsonObject = buildJsonObject {
    put("type", TYPE_SCORE)
    if (value.instructions != null) put("instructions", content(value.instructions))
    put("criteria", content(value.criteria))
  }

  override fun decodeFields(obj: JsonObject): Score {
    val criteria =
      obj["criteria"] as? JsonArray
        ?: throw SerializationException("Score question requires a criteria array.")
    return Score(instructions = obj["instructions"], criteria = criteria)
  }
}

/**
 * Serializes any [Answer] as a JSON object whose `type` is `choice`, `binary`, `score` or
 * `refusal`.
 */
internal object AnswerSerializer : ClassifierJsonSerializer<Answer>("answer") {
  override fun encode(value: Answer): JsonObject =
    when (value) {
      is ChoiceAnswer -> ChoiceAnswerSerializer.encode(value)
      is BinaryAnswer -> BinaryAnswerSerializer.encode(value)
      is ScoreAnswer -> ScoreAnswerSerializer.encode(value)
      is RefusalAnswer -> RefusalAnswerSerializer.encode(value)
    }

  override fun decodeFields(obj: JsonObject): Answer =
    when (readRequiredString(obj, "type")) {
      TYPE_CHOICE -> ChoiceAnswerSerializer.decode(obj)
      TYPE_BINARY -> BinaryAnswerSerializer.decode(obj)
      TYPE_SCORE -> ScoreAnswerSerializer.decode(obj)
      TYPE_REFUSAL -> RefusalAnswerSerializer.decode(obj)
      else -> throw SerializationException("Unsupported answer type.")
    }
}

/** Serializes a [ChoiceAnswer], omitting an absent `confidence`. */
internal object ChoiceAnswerSerializer :
  ClassifierJsonSerializer<ChoiceAnswer>("choice answer", CHOICE_ANSWER_KEYS, TYPE_CHOICE) {
  override fun encode(value: ChoiceAnswer): JsonObject = buildJsonObject {
    put("type", TYPE_CHOICE)
    put("choice", value.choice)
    put("probabilities", JsonObject(value.probabilities.mapValues { (_, p) -> JsonPrimitive(p) }))
    if (value.confidence != null) put("confidence", value.confidence)
  }

  override fun decodeFields(obj: JsonObject): ChoiceAnswer {
    val probabilities =
      obj["probabilities"] as? JsonObject
        ?: throw SerializationException("ChoiceAnswer requires a probabilities object.")
    return ChoiceAnswer(
      choice = readRequiredString(obj, "choice"),
      probabilities = probabilities.mapValues { (_, p) -> readFiniteDouble(p, "probability") },
      confidence = readOptionalDouble(obj, "confidence"),
    )
  }
}

/** Serializes a [BinaryAnswer], omitting an absent `confidence`. */
internal object BinaryAnswerSerializer :
  ClassifierJsonSerializer<BinaryAnswer>("binary answer", BINARY_ANSWER_KEYS, TYPE_BINARY) {
  override fun encode(value: BinaryAnswer): JsonObject = buildJsonObject {
    put("type", TYPE_BINARY)
    put("probability", value.probability)
    if (value.confidence != null) put("confidence", value.confidence)
  }

  override fun decodeFields(obj: JsonObject): BinaryAnswer =
    BinaryAnswer(
      probability = readFiniteDouble(obj["probability"], "probability"),
      confidence = readOptionalDouble(obj, "confidence"),
    )
}

/** Serializes a [ScoreAnswer]; `probabilities` keys are the rubric levels as decimal strings. */
internal object ScoreAnswerSerializer :
  ClassifierJsonSerializer<ScoreAnswer>("score answer", SCORE_ANSWER_KEYS, TYPE_SCORE) {
  override fun encode(value: ScoreAnswer): JsonObject = buildJsonObject {
    put("type", TYPE_SCORE)
    put("score", value.score)
    put(
      "probabilities",
      JsonObject(
        value.probabilities.entries.associate { (level, p) -> "$level" to JsonPrimitive(p) }
      ),
    )
    if (value.confidence != null) put("confidence", value.confidence)
  }

  override fun decodeFields(obj: JsonObject): ScoreAnswer {
    val probabilities =
      obj["probabilities"] as? JsonObject
        ?: throw SerializationException("ScoreAnswer requires a probabilities object.")
    return ScoreAnswer(
      score = readFiniteDouble(obj["score"], "score"),
      probabilities =
        probabilities.entries.associate { (level, p) ->
          readLevel(level) to readFiniteDouble(p, "probability")
        },
      confidence = readOptionalDouble(obj, "confidence"),
    )
  }

  private fun readLevel(rawLevel: String): Int {
    if (!SchemaUtils.JSON_INTEGER.matches(rawLevel)) {
      throw SerializationException("ScoreAnswer probability keys must be integers.")
    }
    return rawLevel.toIntOrNull()
      ?: throw SerializationException("ScoreAnswer probability key is out of the Int range.")
  }
}

/** Serializes [RefusalAnswer] as `{"type": "refusal"}`. */
internal object RefusalAnswerSerializer :
  ClassifierJsonSerializer<RefusalAnswer>("refusal answer", REFUSAL_ANSWER_KEYS, TYPE_REFUSAL) {
  override fun encode(value: RefusalAnswer): JsonObject = buildJsonObject {
    put("type", TYPE_REFUSAL)
  }

  override fun decodeFields(obj: JsonObject): RefusalAnswer = RefusalAnswer
}

/** Serializes a [ClassifierResponse] with an `answers` object and an optional `model_version`. */
internal object ClassifierResponseSerializer :
  ClassifierJsonSerializer<ClassifierResponse>("classifier response", RESPONSE_KEYS) {
  override fun encode(value: ClassifierResponse): JsonObject = buildJsonObject {
    put(
      "answers",
      JsonObject(value.answers.mapValues { (_, answer) -> AnswerSerializer.encode(answer) }),
    )
    if (value.modelVersion != null) put("model_version", value.modelVersion)
  }

  override fun decodeFields(obj: JsonObject): ClassifierResponse {
    val rawAnswers =
      obj["answers"] as? JsonObject
        ?: throw SerializationException("ClassifierResponse requires an answers object.")
    val answers = rawAnswers.mapValues { (_, raw) ->
      AnswerSerializer.decode(
        raw as? JsonObject ?: throw SerializationException("Each answer must be a JSON object.")
      )
    }
    return ClassifierResponse(
      answers = answers,
      modelVersion = readOptionalString(obj, "model_version"),
    )
  }
}

private fun content(value: Any?): JsonElement =
  anyToJsonElement(value) { throw SerializationException("Unsupported JSON content value.") }

private fun JsonElement.stringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.nonNull(field: String): JsonElement? =
  this[field]?.takeUnless { it is JsonNull }

private fun readRequiredString(obj: JsonObject, field: String): String =
  readString(obj[field], field)

private fun readOptionalString(obj: JsonObject, field: String): String? =
  obj.nonNull(field)?.let { readString(it, field) }

private fun readString(element: JsonElement?, field: String): String =
  element?.stringOrNull() ?: throw SerializationException("Missing or non-string '$field' field.")

private fun readOptionalDouble(obj: JsonObject, field: String): Double? =
  obj.nonNull(field)?.let { readFiniteDouble(it, field) }

private fun readFiniteDouble(element: JsonElement?, field: String): Double {
  val primitive =
    element as? JsonPrimitive ?: throw SerializationException("Missing or non-numeric $field.")
  if (primitive.isString || !SchemaUtils.JSON_NUMBER.matches(primitive.content)) {
    throw SerializationException("Non-numeric $field.")
  }
  return primitive.doubleOrNull?.takeIf { it.isFinite() }
    ?: throw SerializationException("Non-finite $field.")
}
