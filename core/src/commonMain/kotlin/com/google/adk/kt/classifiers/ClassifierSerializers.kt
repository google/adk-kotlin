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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.put

private const val TYPE_CHOICE = "choice"
private const val TYPE_BINARY = "binary"
private const val TYPE_SCORE = "score"
private const val TYPE_REFUSAL = "refusal"

private const val FIELD_TYPE = "type"
private const val FIELD_INSTRUCTIONS = "instructions"
private const val FIELD_CRITERIA = "criteria"
private const val FIELD_CHOICE = "choice"
private const val FIELD_PROBABILITIES = "probabilities"
private const val FIELD_PROBABILITY = "probability"
private const val FIELD_SCORE = "score"
private const val FIELD_CONFIDENCE = "confidence"
private const val FIELD_ANSWERS = "answers"
private const val FIELD_MODEL_VERSION = "model_version"

private val QUESTION_KEYS = setOf(FIELD_TYPE, FIELD_INSTRUCTIONS, FIELD_CRITERIA)

/**
 * Base for classifier serializers that each read and write a single JSON object in JSON formats
 * only. Top-level error messages identify the object by [kind], and no error quotes the payload;
 * decoding rejects keys outside a non-empty [allowedKeys] or a `type` other than [expectedType],
 * while allowing `type` to be omitted. `decodeJsonElement` parses JSON arrays recursively on the
 * call stack before [ClassifierContent]'s depth check runs, so thousands of nested arrays in
 * untrusted text can still overflow the stack.
 */
internal abstract class ClassifierJsonSerializer<T>(
  private val kind: String,
  private val allowedKeys: Set<String> = emptySet(),
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
    if (allowedKeys.isNotEmpty() && obj.keys.any { it !in allowedKeys }) {
      throw SerializationException("Unknown field in $kind JSON.")
    }
    val type = obj[FIELD_TYPE]
    if (expectedType != null && type != null && type.stringOrNull() != expectedType) {
      throw SerializationException("Expected '$expectedType' as the $kind type.")
    }
    return try {
      decodeFields(obj)
    } catch (e: SerializationException) {
      // Rethrown before IllegalArgumentException, which SerializationException extends.
      throw e
    } catch (e: IllegalArgumentException) {
      // The constructors' messages name only types and counts, never the payload.
      throw SerializationException(e.message ?: "Invalid $kind payload.", e)
    }
  }

  protected abstract fun decodeFields(obj: JsonObject): T
}

/** Serializes any [Question] as a JSON object whose `type` is `choice`, `binary` or `score`. */
internal object QuestionSerializer : ClassifierJsonSerializer<Question>(kind = "question") {
  override fun encode(value: Question): JsonObject =
    when (value) {
      is Choice -> ChoiceSerializer.encode(value)
      is Binary -> BinarySerializer.encode(value)
      is Score -> ScoreSerializer.encode(value)
    }

  override fun decodeFields(obj: JsonObject): Question =
    when (readRequiredString(obj, FIELD_TYPE)) {
      TYPE_CHOICE -> ChoiceSerializer.decode(obj)
      TYPE_BINARY -> BinarySerializer.decode(obj)
      TYPE_SCORE -> ScoreSerializer.decode(obj)
      else -> throw SerializationException("Unsupported question type.")
    }
}

/** Serializes a [Choice], writing `null` for an absent `instructions` or label description. */
internal object ChoiceSerializer :
  ClassifierJsonSerializer<Choice>(
    kind = "choice question",
    allowedKeys = QUESTION_KEYS,
    expectedType = TYPE_CHOICE,
  ) {
  override fun encode(value: Choice): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_CHOICE)
    put(FIELD_INSTRUCTIONS, encodeContent(value.instructions))
    put(FIELD_CRITERIA, JsonObject(value.criteria.mapValues { (_, v) -> encodeContent(v) }))
  }

  override fun decodeFields(obj: JsonObject): Choice =
    Choice(
      instructions = decodeOptionalContent(obj[FIELD_INSTRUCTIONS]),
      criteria = readObject(obj, FIELD_CRITERIA).mapValues { (_, v) -> decodeOptionalContent(v) },
    )
}

/** Serializes a [Binary], writing `null` for absent `instructions` and `criteria`. */
internal object BinarySerializer :
  ClassifierJsonSerializer<Binary>(
    kind = "binary question",
    allowedKeys = QUESTION_KEYS,
    expectedType = TYPE_BINARY,
  ) {
  override fun encode(value: Binary): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_BINARY)
    put(FIELD_INSTRUCTIONS, encodeContent(value.instructions))
    put(FIELD_CRITERIA, encodeContent(value.criteria))
  }

  override fun decodeFields(obj: JsonObject): Binary =
    Binary(
      instructions = decodeOptionalContent(obj[FIELD_INSTRUCTIONS]),
      criteria = decodeOptionalContent(obj[FIELD_CRITERIA]),
    )
}

/** Serializes a [Score]; `criteria` is the array of rubric levels, starting at level 0. */
internal object ScoreSerializer :
  ClassifierJsonSerializer<Score>(
    kind = "score question",
    allowedKeys = QUESTION_KEYS,
    expectedType = TYPE_SCORE,
  ) {
  override fun encode(value: Score): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_SCORE)
    put(FIELD_INSTRUCTIONS, encodeContent(value.instructions))
    put(FIELD_CRITERIA, JsonArray(value.criteria.map(::encodeContent)))
  }

  override fun decodeFields(obj: JsonObject): Score {
    val criteria =
      obj[FIELD_CRITERIA] as? JsonArray
        ?: throw SerializationException("Score question requires a criteria array.")
    return Score(
      instructions = decodeOptionalContent(obj[FIELD_INSTRUCTIONS]),
      criteria = criteria.map(::decodeContent),
    )
  }
}

/**
 * Serializes any [Answer] as a JSON object whose `type` is `choice`, `binary`, `score` or
 * `refusal`.
 */
internal object AnswerSerializer : ClassifierJsonSerializer<Answer>(kind = "answer") {
  override fun encode(value: Answer): JsonObject =
    when (value) {
      is ChoiceAnswer -> ChoiceAnswerSerializer.encode(value)
      is BinaryAnswer -> BinaryAnswerSerializer.encode(value)
      is ScoreAnswer -> ScoreAnswerSerializer.encode(value)
      is RefusalAnswer -> RefusalAnswerSerializer.encode(value)
    }

  override fun decodeFields(obj: JsonObject): Answer =
    when (readRequiredString(obj, FIELD_TYPE)) {
      TYPE_CHOICE -> ChoiceAnswerSerializer.decode(obj)
      TYPE_BINARY -> BinaryAnswerSerializer.decode(obj)
      TYPE_SCORE -> ScoreAnswerSerializer.decode(obj)
      TYPE_REFUSAL -> RefusalAnswerSerializer.decode(obj)
      else -> throw SerializationException("Unsupported answer type.")
    }
}

/** Serializes a [ChoiceAnswer], writing `null` when `confidence` is absent. */
internal object ChoiceAnswerSerializer :
  ClassifierJsonSerializer<ChoiceAnswer>(
    kind = "choice answer",
    allowedKeys = setOf(FIELD_TYPE, FIELD_CHOICE, FIELD_PROBABILITIES, FIELD_CONFIDENCE),
    expectedType = TYPE_CHOICE,
  ) {
  override fun encode(value: ChoiceAnswer): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_CHOICE)
    put(FIELD_CHOICE, value.choice)
    put(
      FIELD_PROBABILITIES,
      JsonObject(value.probabilities.mapValues { (_, p) -> JsonPrimitive(p) }),
    )
    put(FIELD_CONFIDENCE, value.confidence)
  }

  override fun decodeFields(obj: JsonObject): ChoiceAnswer =
    ChoiceAnswer(
      choice = readRequiredString(obj, FIELD_CHOICE),
      probabilities =
        readObject(obj, FIELD_PROBABILITIES).mapValues { (_, p) -> readDouble(p, "probability") },
      confidence = readOptionalDouble(obj, FIELD_CONFIDENCE),
    )
}

/** Serializes a [BinaryAnswer], writing `null` when `confidence` is absent. */
internal object BinaryAnswerSerializer :
  ClassifierJsonSerializer<BinaryAnswer>(
    kind = "binary answer",
    allowedKeys = setOf(FIELD_TYPE, FIELD_PROBABILITY, FIELD_CONFIDENCE),
    expectedType = TYPE_BINARY,
  ) {
  override fun encode(value: BinaryAnswer): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_BINARY)
    put(FIELD_PROBABILITY, value.probability)
    put(FIELD_CONFIDENCE, value.confidence)
  }

  override fun decodeFields(obj: JsonObject): BinaryAnswer =
    BinaryAnswer(
      probability = readDouble(obj[FIELD_PROBABILITY], FIELD_PROBABILITY),
      confidence = readOptionalDouble(obj, FIELD_CONFIDENCE),
    )
}

/** Serializes a [ScoreAnswer]; `probabilities` keys are the rubric levels as decimal strings. */
internal object ScoreAnswerSerializer :
  ClassifierJsonSerializer<ScoreAnswer>(
    kind = "score answer",
    allowedKeys = setOf(FIELD_TYPE, FIELD_SCORE, FIELD_PROBABILITIES, FIELD_CONFIDENCE),
    expectedType = TYPE_SCORE,
  ) {
  override fun encode(value: ScoreAnswer): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_SCORE)
    put(FIELD_SCORE, value.score)
    put(
      FIELD_PROBABILITIES,
      JsonObject(
        value.probabilities.entries.associate { (level, p) -> "$level" to JsonPrimitive(p) }
      ),
    )
    put(FIELD_CONFIDENCE, value.confidence)
  }

  override fun decodeFields(obj: JsonObject): ScoreAnswer =
    ScoreAnswer(
      score = readDouble(obj[FIELD_SCORE], FIELD_SCORE),
      probabilities =
        readObject(obj, FIELD_PROBABILITIES).entries.associate { (level, p) ->
          readLevel(level) to readDouble(p, "probability")
        },
      confidence = readOptionalDouble(obj, FIELD_CONFIDENCE),
    )

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
  ClassifierJsonSerializer<RefusalAnswer>(
    kind = "refusal answer",
    allowedKeys = setOf(FIELD_TYPE),
    expectedType = TYPE_REFUSAL,
  ) {
  override fun encode(value: RefusalAnswer): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_REFUSAL)
  }

  override fun decodeFields(obj: JsonObject): RefusalAnswer = RefusalAnswer
}

/** Serializes a [ClassifierResponse] with an `answers` object and `model_version`. */
internal object ClassifierResponseSerializer :
  ClassifierJsonSerializer<ClassifierResponse>(
    kind = "classifier response",
    allowedKeys = setOf(FIELD_ANSWERS, FIELD_MODEL_VERSION),
  ) {
  override fun encode(value: ClassifierResponse): JsonObject = buildJsonObject {
    put(
      FIELD_ANSWERS,
      JsonObject(value.answers.mapValues { (_, answer) -> AnswerSerializer.encode(answer) }),
    )
    put(FIELD_MODEL_VERSION, value.modelVersion)
  }

  override fun decodeFields(obj: JsonObject): ClassifierResponse =
    ClassifierResponse(
      answers =
        readObject(obj, FIELD_ANSWERS).mapValues { (_, raw) ->
          AnswerSerializer.decode(
            raw as? JsonObject ?: throw SerializationException("Each answer must be a JSON object.")
          )
        },
      modelVersion = readOptionalString(obj, FIELD_MODEL_VERSION),
    )
}

private fun encodeContent(value: ClassifierContent?): JsonElement =
  when (value) {
    null -> JsonNull
    is ClassifierContent.Text -> JsonPrimitive(value.text)
    is ClassifierContent.JsonObject -> value.json
    is ClassifierContent.JsonArray -> value.json
  }

private fun decodeOptionalContent(element: JsonElement?): ClassifierContent? =
  if (element == null || element is JsonNull) null else decodeContent(element)

private fun decodeContent(element: JsonElement): ClassifierContent =
  when (element) {
    is JsonObject -> ClassifierContent.JsonObject(element)
    is JsonArray -> ClassifierContent.JsonArray(element)
    is JsonPrimitive ->
      element.stringOrNull()?.let(ClassifierContent::Text)
        ?: throw SerializationException(
          "Expected string, object, or array JSON content in question."
        )
  }

private fun JsonElement.stringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.fieldOrNull(field: String): JsonElement? =
  this[field]?.takeUnless { it is JsonNull }

private fun readObject(obj: JsonObject, field: String): JsonObject =
  obj[field] as? JsonObject ?: throw SerializationException("Missing or non-object '$field' field.")

private fun readRequiredString(obj: JsonObject, field: String): String =
  readString(obj[field], field)

private fun readOptionalString(obj: JsonObject, field: String): String? =
  obj.fieldOrNull(field)?.let { readString(it, field) }

private fun readString(element: JsonElement?, field: String): String =
  element?.stringOrNull() ?: throw SerializationException("Missing or non-string '$field' field.")

private fun readOptionalDouble(obj: JsonObject, field: String): Double? =
  obj.fieldOrNull(field)?.let { readDouble(it, field) }

private fun readDouble(element: JsonElement?, field: String): Double {
  val primitive =
    element as? JsonPrimitive ?: throw SerializationException("Missing or non-numeric $field.")
  if (primitive.isString || !SchemaUtils.JSON_NUMBER.matches(primitive.content)) {
    throw SerializationException("Non-numeric $field.")
  }
  return primitive.double
}
