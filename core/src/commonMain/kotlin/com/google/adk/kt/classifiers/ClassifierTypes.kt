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

/** A typed question answered by a [Classifier]: [Choice], [Binary], or [Score]. */
@ExperimentalClassifierApi sealed interface Question

/**
 * Asks a [Classifier] to pick one of two or more named labels.
 *
 * @property instructions What to decide, as text or JSON content.
 * @property criteria Maps each label (at least two) to an optional description of when it applies.
 */
@ExperimentalClassifierApi
class Choice @JvmOverloads constructor(instructions: Any? = null, criteria: Map<String, Any?>) :
  Question {
  val instructions: Any? = freezeOptionalJsonContent(instructions)
  val criteria: Map<String, Any?> = freezeChoiceCriteria(criteria)

  fun copy(
    instructions: Any? = this.instructions,
    criteria: Map<String, Any?> = this.criteria,
  ): Choice = Choice(instructions = instructions, criteria = criteria)

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is Choice && instructions == other.instructions && criteria == other.criteria)

  override fun hashCode(): Int = 31 * (instructions?.hashCode() ?: 0) + criteria.hashCode()

  override fun toString(): String = "Choice(instructions=$instructions, criteria=$criteria)"

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
    private var instructions: Any? = null
    private var criteria: MutableMap<String, Any?>? = null

    fun instructions(instructions: Any?): Builder = apply { this.instructions = instructions }

    fun criteria(criteria: Map<String, Any?>): Builder = apply {
      this.criteria = criteria.toMutableMap()
    }

    /**
     * Adds or replaces one label in [Choice.criteria], allowing `null` descriptions that `Map.of`
     * rejects.
     */
    @JvmOverloads
    fun criterion(label: String, description: Any? = null): Builder = apply {
      val map = criteria ?: mutableMapOf<String, Any?>().also { criteria = it }
      map[label] = description
    }

    fun build(): Choice =
      Choice(
        instructions = instructions,
        criteria = checkNotNull(criteria) { "Choice.Builder requires criteria to be set." },
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Asks a [Classifier] for the probability that a statement is true.
 *
 * @property instructions The yes-or-no question, as text or JSON content.
 * @property criteria Optional description of what counts as true; anything else counts as false.
 */
@ExperimentalClassifierApi
class Binary @JvmOverloads constructor(instructions: Any? = null, criteria: Any? = null) :
  Question {
  val instructions: Any? = freezeOptionalJsonContent(instructions)
  val criteria: Any? = freezeOptionalJsonContent(criteria)

  fun copy(instructions: Any? = this.instructions, criteria: Any? = this.criteria): Binary =
    Binary(instructions = instructions, criteria = criteria)

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is Binary && instructions == other.instructions && criteria == other.criteria)

  override fun hashCode(): Int = 31 * (instructions?.hashCode() ?: 0) + (criteria?.hashCode() ?: 0)

  override fun toString(): String = "Binary(instructions=$instructions, criteria=$criteria)"

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
    private var instructions: Any? = null
    private var criteria: Any? = null

    fun instructions(instructions: Any?): Builder = apply { this.instructions = instructions }

    fun criteria(criteria: Any?): Builder = apply { this.criteria = criteria }

    fun build(): Binary = Binary(instructions = instructions, criteria = criteria)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Asks a [Classifier] to place an input on an ordered rubric starting at level 0.
 *
 * @property instructions What to rate, as text or JSON content.
 * @property criteria One non-null description per rubric level (at least two), starting at 0.
 */
@ExperimentalClassifierApi
class Score @JvmOverloads constructor(instructions: Any? = null, criteria: List<Any>) : Question {
  val instructions: Any? = freezeOptionalJsonContent(instructions)
  val criteria: List<Any> = freezeScoreCriteria(criteria)

  fun copy(instructions: Any? = this.instructions, criteria: List<Any> = this.criteria): Score =
    Score(instructions = instructions, criteria = criteria)

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is Score && instructions == other.instructions && criteria == other.criteria)

  override fun hashCode(): Int = 31 * (instructions?.hashCode() ?: 0) + criteria.hashCode()

  override fun toString(): String = "Score(instructions=$instructions, criteria=$criteria)"

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
    private var instructions: Any? = null
    private var criteria: List<Any>? = null

    fun instructions(instructions: Any?): Builder = apply { this.instructions = instructions }

    fun criteria(criteria: List<Any>): Builder = apply { this.criteria = criteria }

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
 * A [Classifier]'s answer to a [Question]: [ChoiceAnswer], [BinaryAnswer], [ScoreAnswer], or
 * [RefusalAnswer].
 */
@ExperimentalClassifierApi sealed interface Answer

/**
 * The answer to a [Choice] question.
 *
 * @property choice The most likely label.
 * @property probabilities Maps each label in the question to its probability in `[0, 1]`.
 * @property confidence The provider's confidence in `[0, 1]`, or `null` when unreported.
 */
@ExperimentalClassifierApi
class ChoiceAnswer
@JvmOverloads
constructor(
  val choice: String,
  probabilities: Map<String, Double>,
  val confidence: Double? = null,
) : Answer {
  val probabilities: Map<String, Double> = freezeChoiceProbabilities(probabilities)

  init {
    validateConfidence(confidence)
  }

  fun copy(
    choice: String = this.choice,
    probabilities: Map<String, Double> = this.probabilities,
    confidence: Double? = this.confidence,
  ): ChoiceAnswer =
    ChoiceAnswer(choice = choice, probabilities = probabilities, confidence = confidence)

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is ChoiceAnswer &&
        choice == other.choice &&
        probabilities == other.probabilities &&
        compareValues(confidence, other.confidence) == 0)

  override fun hashCode(): Int {
    var result = choice.hashCode()
    result = 31 * result + probabilities.hashCode()
    result = 31 * result + (confidence?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "ChoiceAnswer(choice=$choice, probabilities=$probabilities, confidence=$confidence)"

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
class ScoreAnswer
@JvmOverloads
constructor(val score: Double, probabilities: Map<Int, Double>, val confidence: Double? = null) :
  Answer {
  val probabilities: Map<Int, Double> = freezeScoreProbabilities(probabilities)

  init {
    require(score.isFinite()) { "ScoreAnswer score must be a finite number." }
    validateConfidence(confidence)
  }

  fun copy(
    score: Double = this.score,
    probabilities: Map<Int, Double> = this.probabilities,
    confidence: Double? = this.confidence,
  ): ScoreAnswer =
    ScoreAnswer(score = score, probabilities = probabilities, confidence = confidence)

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is ScoreAnswer &&
        compareValues(score, other.score) == 0 &&
        probabilities == other.probabilities &&
        compareValues(confidence, other.confidence) == 0)

  override fun hashCode(): Int {
    var result = score.hashCode()
    result = 31 * result + probabilities.hashCode()
    result = 31 * result + (confidence?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "ScoreAnswer(score=$score, probabilities=$probabilities, confidence=$confidence)"

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
 * The answers produced by a single [Classifier.classify] call.
 *
 * @property answers Maps each caller-supplied question name to its [Answer].
 * @property modelVersion The concrete model version reported by the provider, or `null` when
 *   unreported.
 */
@ExperimentalClassifierApi
class ClassifierResponse
@JvmOverloads
constructor(answers: Map<String, Answer>, val modelVersion: String? = null) {
  val answers: Map<String, Answer> = freezeAnswers(answers)

  fun copy(
    answers: Map<String, Answer> = this.answers,
    modelVersion: String? = this.modelVersion,
  ): ClassifierResponse = ClassifierResponse(answers = answers, modelVersion = modelVersion)

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is ClassifierResponse &&
        answers == other.answers &&
        modelVersion == other.modelVersion)

  override fun hashCode(): Int = 31 * answers.hashCode() + (modelVersion?.hashCode() ?: 0)

  override fun toString(): String =
    "ClassifierResponse(answers=$answers, modelVersion=$modelVersion)"

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
