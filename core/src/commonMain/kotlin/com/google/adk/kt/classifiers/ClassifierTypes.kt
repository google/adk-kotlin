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

package com.google.adk.kt.classifiers

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * A typed question answered by a [Classifier]: [Choice], [Binary], or [Score]. Its instructions and
 * criteria are [ClassifierContent]: text, or a JSON object or array.
 */
@ExperimentalClassifierApi
sealed interface Question {
  /** What to decide, or `null`. */
  val instructions: ClassifierContent?
}

/**
 * A [Classifier]'s answer to a [Question]: [ChoiceAnswer], [BinaryAnswer], [ScoreAnswer], or
 * [RefusalAnswer].
 */
@ExperimentalClassifierApi sealed interface Answer

/**
 * Asks a [Classifier] to pick one of two or more named labels.
 *
 * @property instructions What to decide.
 * @property criteria Maps each label (at least two) to an optional description of when it applies.
 */
@ExperimentalClassifierApi
data class Choice
@JvmOverloads
constructor(
  override val instructions: ClassifierContent? = null,
  val criteria: Map<String, ClassifierContent?>,
) : Question {
  init {
    validateChoiceCriteria(criteria)
  }

  /**
   * Creates a [Choice] with plain-text instructions and label descriptions. Because [instructions]
   * has no default here, a call that omits it resolves to the primary constructor.
   */
  constructor(
    instructions: String?,
    criteria: Map<String, String?>,
  ) : this(instructions?.let(ClassifierContent::Text), textCriteria(criteria))

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().instructions(instructions).criteria(criteria)

  /**
   * Fluent builder for [Choice], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var instructions: ClassifierContent? = null
    private var criteria: MutableMap<String, ClassifierContent?>? = null

    fun instructions(instructions: ClassifierContent?): Builder = apply {
      this.instructions = instructions
    }

    /** Sets plain-text [instructions], or clears them if `null`. */
    fun instructions(instructions: String?): Builder = apply {
      this.instructions = instructions?.let(ClassifierContent::Text)
    }

    fun criteria(criteria: Map<String, ClassifierContent?>): Builder = apply {
      this.criteria = criteria.toMutableMap()
    }

    /**
     * Adds or replaces one label in [Choice.criteria]; a `null` [description], which `Map.of`
     * rejects, means none.
     */
    @JvmOverloads
    fun criterion(label: String, description: ClassifierContent? = null): Builder = apply {
      criteria = (criteria ?: mutableMapOf()).also { it[label] = description }
    }

    /**
     * Adds or replaces one label in [Choice.criteria] with a plain-text [description], or none if
     * `null`.
     */
    fun criterion(label: String, description: String?): Builder =
      criterion(label, description?.let(ClassifierContent::Text))

    fun build(): Choice =
      Choice(
        instructions = instructions,
        criteria = checkNotNull(criteria) { "Choice.Builder requires criteria to be set." }.toMap(),
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Asks a [Classifier] for the probability that a statement is true.
 *
 * @property instructions The yes-or-no question.
 * @property criteria Optional description of what counts as true; anything else counts as false.
 */
@ExperimentalClassifierApi
data class Binary
@JvmOverloads
constructor(
  override val instructions: ClassifierContent? = null,
  val criteria: ClassifierContent? = null,
) : Question {

  /** Creates a [Binary] with plain-text [instructions] and no criteria. */
  constructor(instructions: String) : this(ClassifierContent.Text(instructions))

  /** Creates a [Binary] whose criteria, and instructions if given, are plain text. */
  constructor(
    instructions: String? = null,
    criteria: String,
  ) : this(instructions?.let(ClassifierContent::Text), ClassifierContent.Text(criteria))

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().instructions(instructions).criteria(criteria)

  /**
   * Fluent builder for [Binary], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var instructions: ClassifierContent? = null
    private var criteria: ClassifierContent? = null

    fun instructions(instructions: ClassifierContent?): Builder = apply {
      this.instructions = instructions
    }

    /** Sets plain-text [instructions], or clears them if `null`. */
    fun instructions(instructions: String?): Builder = apply {
      this.instructions = instructions?.let(ClassifierContent::Text)
    }

    fun criteria(criteria: ClassifierContent?): Builder = apply { this.criteria = criteria }

    /** Sets plain-text [criteria], or clears them if `null`. */
    fun criteria(criteria: String?): Builder = apply {
      this.criteria = criteria?.let(ClassifierContent::Text)
    }

    fun build(): Binary = Binary(instructions = instructions, criteria = criteria)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Asks a [Classifier] to place an input on an ordered rubric starting at level 0.
 *
 * @property instructions What to rate.
 * @property criteria One description per rubric level (at least two), starting at 0.
 */
@ExperimentalClassifierApi
data class Score
@JvmOverloads
constructor(
  override val instructions: ClassifierContent? = null,
  val criteria: List<ClassifierContent>,
) : Question {
  init {
    validateScoreCriteria(criteria)
  }

  /**
   * Creates a [Score] with plain-text instructions and level descriptions. Because [instructions]
   * has no default here, a call that omits it resolves to the primary constructor.
   */
  constructor(
    instructions: String?,
    criteria: List<String>,
  ) : this(instructions?.let(ClassifierContent::Text), textLevels(criteria))

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().instructions(instructions).criteria(criteria)

  /**
   * Fluent builder for [Score], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var instructions: ClassifierContent? = null
    private var criteria: List<ClassifierContent>? = null

    fun instructions(instructions: ClassifierContent?): Builder = apply {
      this.instructions = instructions
    }

    /** Sets plain-text [instructions], or clears them if `null`. */
    fun instructions(instructions: String?): Builder = apply {
      this.instructions = instructions?.let(ClassifierContent::Text)
    }

    fun criteria(criteria: List<ClassifierContent>): Builder = apply { this.criteria = criteria }

    /** Sets plain-text criteria, one per rubric level starting at 0; a score needs at least two. */
    fun criteria(first: String, second: String, vararg rest: String): Builder = apply {
      this.criteria = textLevels(listOf(first, second) + rest)
    }

    fun build(): Score =
      Score(
        instructions = instructions,
        criteria = checkNotNull(criteria) { "Score.Builder requires criteria to be set." },
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * The answer to a [Choice] question.
 *
 * @property choice The most likely label.
 * @property probabilities Maps each label in the question to its probability in `[0, 1]`.
 * @property confidence The provider's confidence in `[0, 1]`, or `null` when unreported.
 */
@ExperimentalClassifierApi
data class ChoiceAnswer
@JvmOverloads
constructor(
  val choice: String,
  val probabilities: Map<String, Double>,
  val confidence: Double? = null,
) : Answer {
  init {
    validateProbabilities(probabilities, "ChoiceAnswer probabilities")
    validateConfidence(confidence)
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder().choice(choice).probabilities(probabilities).confidence(confidence)

  /**
   * Fluent builder for [ChoiceAnswer], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var choice: String? = null
    private var probabilities: Map<String, Double>? = null
    private var confidence: Double? = null

    fun choice(choice: String): Builder = apply { this.choice = choice }

    fun probabilities(probabilities: Map<String, Double>): Builder = apply {
      this.probabilities = probabilities
    }

    fun confidence(confidence: Double?): Builder = apply { this.confidence = confidence }

    fun build(): ChoiceAnswer =
      ChoiceAnswer(
        choice = checkNotNull(choice) { "ChoiceAnswer.Builder requires choice to be set." },
        probabilities =
          checkNotNull(probabilities) { "ChoiceAnswer.Builder requires probabilities to be set." },
        confidence = confidence,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * The answer to a [Binary] question.
 *
 * @property probability The probability in `[0, 1]` that the statement is true.
 * @property confidence The provider's confidence in `[0, 1]`, or `null` when unreported.
 */
@ExperimentalClassifierApi
data class BinaryAnswer
@JvmOverloads
constructor(val probability: Double, val confidence: Double? = null) : Answer {
  init {
    validateProbability("probability", probability)
    validateConfidence(confidence)
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().probability(probability).confidence(confidence)

  /**
   * Fluent builder for [BinaryAnswer], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var probability: Double? = null
    private var confidence: Double? = null

    fun probability(probability: Double): Builder = apply { this.probability = probability }

    fun confidence(confidence: Double?): Builder = apply { this.confidence = confidence }

    fun build(): BinaryAnswer =
      BinaryAnswer(
        probability =
          checkNotNull(probability) { "BinaryAnswer.Builder requires probability to be set." },
        confidence = confidence,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * The answer to a [Score] question.
 *
 * @property score The expected rubric level, weighted by [probabilities].
 * @property probabilities Maps each 0-based rubric level to its probability in `[0, 1]`.
 * @property confidence The provider's confidence in `[0, 1]`, or `null` when unreported.
 */
@ExperimentalClassifierApi
data class ScoreAnswer
@JvmOverloads
constructor(
  val score: Double,
  val probabilities: Map<Int, Double>,
  val confidence: Double? = null,
) : Answer {
  init {
    require(score.isFinite()) { "ScoreAnswer score must be a finite number." }
    validateProbabilities(probabilities, "ScoreAnswer probabilities")
    validateConfidence(confidence)
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder().score(score).probabilities(probabilities).confidence(confidence)

  /**
   * Fluent builder for [ScoreAnswer], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var score: Double? = null
    private var probabilities: Map<Int, Double>? = null
    private var confidence: Double? = null

    fun score(score: Double): Builder = apply { this.score = score }

    fun probabilities(probabilities: Map<Int, Double>): Builder = apply {
      this.probabilities = probabilities
    }

    fun confidence(confidence: Double?): Builder = apply { this.confidence = confidence }

    fun build(): ScoreAnswer =
      ScoreAnswer(
        score = checkNotNull(score) { "ScoreAnswer.Builder requires score to be set." },
        probabilities =
          checkNotNull(probabilities) { "ScoreAnswer.Builder requires probabilities to be set." },
        confidence = confidence,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Indicates that a provider declined to produce an answer for a question.
 *
 * Reporting refusals per question lets one [Classifier.classify] batch still return answers for the
 * remaining questions.
 */
@ExperimentalClassifierApi data object RefusalAnswer : Answer

/**
 * The input a [Classifier] answers questions about: [ClassifierContent] (text, or a JSON object or
 * array) or multimodal [Content] parts.
 */
@ExperimentalClassifierApi
sealed interface ClassifierInput {

  /**
   * [com.google.adk.kt.types.Content] input; see [Classifier.classify] for which parts are
   * accepted.
   */
  @ExperimentalClassifierApi
  data class Content(val content: com.google.adk.kt.types.Content) : ClassifierInput

  companion object {
    /** Returns [text] as plain-text input. */
    @JvmStatic fun of(text: String): ClassifierInput = ClassifierContent.Text(text)
  }
}

/**
 * Text or structured data that a [Classifier] reads, as the input or as a question's instructions
 * or criteria: plain [Text], or a [JsonObject] or [JsonArray]. It mirrors Python ADK's
 * `JSONContent`.
 */
@ExperimentalClassifierApi
sealed interface ClassifierContent : ClassifierInput {

  /** Plain text. */
  @ExperimentalClassifierApi data class Text(val text: String) : ClassifierContent

  /**
   * A JSON object. Creating one throws [IllegalArgumentException] if [json] nests more than 200
   * levels deep or holds a non-finite number, an integer beyond a `Long`, or a literal that is not
   * JSON.
   */
  @ExperimentalClassifierApi
  class JsonObject(json: kotlinx.serialization.json.JsonObject) : ClassifierContent {
    /** The object, with each number written as the `Long` or `Double` it parses to. */
    val json: kotlinx.serialization.json.JsonObject = normalizedJson(json)

    override fun equals(other: Any?): Boolean = other is JsonObject && other.json == json

    override fun hashCode(): Int = json.hashCode()

    override fun toString(): String = "JsonObject(json=$json)"
  }

  /**
   * A JSON array. Creating one throws [IllegalArgumentException] under the same conditions as
   * [JsonObject].
   */
  @ExperimentalClassifierApi
  class JsonArray(json: kotlinx.serialization.json.JsonArray) : ClassifierContent {
    /** The array, with each number written as the `Long` or `Double` it parses to. */
    val json: kotlinx.serialization.json.JsonArray = normalizedJson(json)

    override fun equals(other: Any?): Boolean = other is JsonArray && other.json == json

    override fun hashCode(): Int = json.hashCode()

    override fun toString(): String = "JsonArray(json=$json)"
  }

  companion object {
    /** Returns [text] as plain-text content. */
    @JvmStatic fun of(text: String): ClassifierContent = Text(text)

    /**
     * Parses [json], the text of a JSON object or array, into a [JsonObject] or [JsonArray].
     *
     * @throws IllegalArgumentException if [json] is not a JSON object or array, or fails the checks
     *   of the [JsonObject] or [JsonArray] it would create.
     */
    @JvmStatic fun parse(json: String): ClassifierContent = parseClassifierContent(json)
  }
}

/**
 * A request to a [Classifier] to answer one or more typed [questions] about [input].
 *
 * @property input What the questions are about.
 * @property questions Maps each caller-chosen question ID to its [Question];
 *   [ClassifierResponse.answers] returns one [Answer] under each of these IDs. Must not be empty.
 */
@ExperimentalClassifierApi
data class ClassifierRequest(val input: ClassifierInput, val questions: Map<String, Question>) {
  init {
    require(questions.isNotEmpty()) { "ClassifierRequest requires at least one question." }
    validateMapEntries(questions, "ClassifierRequest questions")
  }

  /** Creates a request that classifies plain [text]. */
  constructor(
    text: String,
    questions: Map<String, Question>,
  ) : this(ClassifierContent.Text(text), questions)

  /** Creates a request that classifies [content]. */
  constructor(
    content: com.google.adk.kt.types.Content,
    questions: Map<String, Question>,
  ) : this(ClassifierInput.Content(content), questions)

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi fun toBuilder(): Builder = Builder().input(input).questions(questions)

  /**
   * Fluent builder for [ClassifierRequest], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var input: ClassifierInput? = null
    private var questions: Map<String, Question>? = null

    fun input(input: ClassifierInput): Builder = apply { this.input = input }

    fun questions(questions: Map<String, Question>): Builder = apply { this.questions = questions }

    fun build(): ClassifierRequest =
      ClassifierRequest(
        input = checkNotNull(input) { "ClassifierRequest.Builder requires input to be set." },
        questions =
          checkNotNull(questions) { "ClassifierRequest.Builder requires questions to be set." },
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * The answers produced by a single [Classifier.classify] call.
 *
 * @property answers Maps each caller-chosen question ID from [ClassifierRequest.questions] to its
 *   [Answer].
 * @property modelVersion The concrete model version reported by the provider, or `null` when
 *   unreported.
 */
@ExperimentalClassifierApi
data class ClassifierResponse
@JvmOverloads
constructor(val answers: Map<String, Answer>, val modelVersion: String? = null) {
  init {
    validateMapEntries(answers, "ClassifierResponse answers")
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().answers(answers).modelVersion(modelVersion)

  /**
   * Fluent builder for [ClassifierResponse], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var answers: Map<String, Answer>? = null
    private var modelVersion: String? = null

    fun answers(answers: Map<String, Answer>): Builder = apply { this.answers = answers }

    fun modelVersion(modelVersion: String?): Builder = apply { this.modelVersion = modelVersion }

    fun build(): ClassifierResponse =
      ClassifierResponse(
        answers =
          checkNotNull(answers) { "ClassifierResponse.Builder requires answers to be set." },
        modelVersion = modelVersion,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
