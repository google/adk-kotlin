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

@file:OptIn(
  ExperimentalClassifierApi::class,
  ExperimentalWorkflowApi::class,
  FrameworkInternalApi::class,
)

package com.google.adk.kt.classifiers

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.encodeToPlainMap
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.BaseNode
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.GraphValidationException
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.NodeExecutionException
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.WorkflowConfigurationException
import com.google.adk.kt.workflow.validateNodeName
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Routes a workflow by asking a [classifier] one [question] about the node's input and emitting the
 * answer as the route: the chosen label of a [Choice], or `true` or `false` for a [Binary], decided
 * by [threshold]. The output is the unchanged input, or the underlying value unwrapped from a
 * [ClassifierInput], so the routed-to node receives what the classifier saw.
 *
 * When the node has routed outgoing edges, graph validation checks that every route is a possible
 * answer and that every possible answer has an edge, unless a default or unconditional edge catches
 * the rest.
 *
 * @property classifier The [Classifier] that answers [question].
 * @property question The [Choice] (without [Route.DEFAULT_ROUTE_SENTINEL]) or [Binary] whose answer
 *   picks the route.
 * @param threshold For a [Binary], the probability in `[0, 1]` at or above which the route is
 *   `true`, or `null` for 0.5. Setting it for a [Choice] is an error.
 * @property answerKey The state key where the serialized [Answer] is stored as a JSON map, or
 *   `null` to store nothing.
 * @throws IllegalArgumentException if [name] is empty or contains `'/'`, `'@'`, or `'.'`.
 * @throws WorkflowConfigurationException if [question] is a [Score] or uses
 *   [Route.DEFAULT_ROUTE_SENTINEL], or if `threshold` is set on a [Choice] or lies outside `[0,
 *   1]`.
 */
@ExperimentalClassifierApi
@ExperimentalWorkflowApi
class ClassifierNode
@JvmOverloads
constructor(
  name: String,
  val classifier: Classifier,
  val question: Question,
  threshold: Double? = null,
  val answerKey: String? = null,
  description: String = "",
  rerunOnResume: Boolean = false,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
) :
  BaseNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
  ) {

  /** The probability at or above which a [Binary] routes `true`. */
  val threshold: Double = threshold ?: 0.5

  init {
    validateNodeName(name)
    when (question) {
      is Score ->
        throw WorkflowConfigurationException(
          "ClassifierNode '$name' cannot route on a Score question; use a Choice or a Binary."
        )
      is Choice -> {
        if (threshold != null) {
          throw WorkflowConfigurationException(
            "ClassifierNode '$name' takes a threshold only with a Binary question; a Choice " +
              "always routes to the label the classifier chose."
          )
        }
        if (Route.DEFAULT_ROUTE_SENTINEL in question.criteria) {
          throw WorkflowConfigurationException(
            "ClassifierNode '$name' cannot route on the label " +
              "\"${Route.DEFAULT_ROUTE_SENTINEL}\", which is reserved for the default route."
          )
        }
      }
      is Binary ->
        if (threshold != null && threshold !in 0.0..1.0) {
          throw WorkflowConfigurationException(
            "ClassifierNode '$name' needs a threshold in [0, 1]."
          )
        }
    }
  }

  /**
   * Classifies [nodeInput] and emits it with the route. Throws [NodeExecutionException] if the
   * input is not a [ClassifierInput], text, a JSON object or array (as a `Map`/`List` or
   * `JsonElement`), or [Content], or if the answer is missing, a refusal, an unknown label, or of
   * the wrong type.
   */
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val request = ClassifierRequest(toClassifierInput(nodeInput), mapOf(QUESTION_NAME to question))
    val response = classifier.classify(request)
    val answer =
      response.answers[QUESTION_NAME]
        ?: throw NodeExecutionException("Classifier for node '$name' returned no answer.")
    val route = routeFor(answer)
    if (answerKey != null) {
      context.updateState(answerKey, encodeToPlainMap(Answer.serializer(), answer))
    }
    emit(Event(output = outputFor(nodeInput), actions = EventActions(route = listOf(route))))
  }

  private fun toClassifierInput(nodeInput: Any?): ClassifierInput =
    try {
      when (nodeInput) {
        null ->
          throw NodeExecutionException("ClassifierNode '$name' received no input to classify.")
        is ClassifierInput -> nodeInput
        is Content -> ClassifierInput.Content(nodeInput)
        is String -> ClassifierContent.Text(nodeInput)
        is JsonPrimitive ->
          if (nodeInput.isString) {
            ClassifierContent.Text(nodeInput.content)
          } else {
            throw unsupportedInput(nodeInput)
          }
        is JsonObject -> ClassifierContent.JsonObject(nodeInput)
        is JsonArray -> ClassifierContent.JsonArray(nodeInput)
        is Map<*, *> -> {
          val content = ClassifierContent.JsonObject(toJsonObject(nodeInput, depth = 0))
          content.json.toContentOrNull()?.let(ClassifierInput::Content) ?: content
        }
        is List<*> -> ClassifierContent.JsonArray(toJsonArray(nodeInput, depth = 0))
        else -> throw unsupportedInput(nodeInput)
      }
    } catch (e: IllegalArgumentException) {
      throw NodeExecutionException("ClassifierNode '$name' received an invalid JSON input.", e)
    }

  private fun unsupportedInput(nodeInput: Any): NodeExecutionException =
    NodeExecutionException(
      "ClassifierNode '$name' requires text, a JSON object or array, or Content input, got " +
        "${typeName(nodeInput)}."
    )

  /**
   * Unwraps a [ClassifierInput] to its JSON-serializable value so [BaseNode] accepts the emitted
   * output, leaving every other input unchanged.
   */
  private fun outputFor(nodeInput: Any?): Any? =
    if (nodeInput !is ClassifierInput) {
      nodeInput
    } else {
      when (nodeInput) {
        is ClassifierContent.Text -> nodeInput.text
        is ClassifierContent.JsonObject -> nodeInput.json
        is ClassifierContent.JsonArray -> nodeInput.json
        is ClassifierInput.Content -> nodeInput.content
      }
    }

  private fun routeFor(answer: Answer): Route =
    when {
      answer is RefusalAnswer ->
        throw NodeExecutionException(
          "Classifier for node '$name' declined to answer, so the node has no route to take."
        )
      question is Choice && answer is ChoiceAnswer -> {
        if (answer.choice !in question.criteria) {
          throw NodeExecutionException(
            "Classifier for node '$name' chose a label that is not one of its question's labels."
          )
        }
        Route.Tag(answer.choice)
      }
      question is Binary && answer is BinaryAnswer -> Route.Flag(answer.probability >= threshold)
      else ->
        throw NodeExecutionException(
          "Classifier for node '$name' returned a ${typeName(answer)} to a " +
            "${typeName(question)} question."
        )
    }

  override fun validateOutgoingEdges(edges: List<Edge>) {
    val edgeRoutes = edges.flatMap { it.routes }.toSet()
    if (edgeRoutes.isEmpty()) return
    val routes =
      when (question) {
        is Choice -> question.criteria.keys.map(Route::Tag).toSet()
        is Binary -> setOf(Route.Flag(true), Route.Flag(false))
        is Score -> error("Score questions are rejected in init.")
      }
    val unknown = edgeRoutes - routes - Route.Default
    if (unknown.isNotEmpty()) {
      throw GraphValidationException(
        "Graph validation failed. ClassifierNode '$name' has edges for routes it never emits: " +
          "${unknown.labels()}. Its routes are ${routes.labels()}."
      )
    }
    val missing = routes - edgeRoutes
    // An unconditional edge always fires, so a route without its own edge still continues there.
    if (missing.isNotEmpty() && Route.Default !in edgeRoutes && edges.none { it.isUnconditional }) {
      throw GraphValidationException(
        "Graph validation failed. ClassifierNode '$name' has no edge for routes " +
          "${missing.labels()}, so the branch would end when the classifier picks one. Add an " +
          "edge for each, or a default or unconditional edge."
      )
    }
  }

  /**
   * Fluent builder for [ClassifierNode], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var classifier: Classifier? = null
    private var question: Question? = null
    private var threshold: Double? = null
    private var answerKey: String? = null
    private var description: String = ""
    private var rerunOnResume: Boolean = false
    private var waitForOutput: Boolean = false
    private var config: NodeConfig = NodeConfig()
    private var inputSchema: Schema? = null
    private var outputSchema: Schema? = null
    private var stateSchema: Schema? = null

    fun name(name: String): Builder = apply { this.name = name }

    fun classifier(classifier: Classifier): Builder = apply { this.classifier = classifier }

    fun question(question: Question): Builder = apply { this.question = question }

    fun threshold(threshold: Double?): Builder = apply { this.threshold = threshold }

    fun answerKey(answerKey: String?): Builder = apply { this.answerKey = answerKey }

    fun description(description: String): Builder = apply { this.description = description }

    fun rerunOnResume(rerunOnResume: Boolean): Builder = apply {
      this.rerunOnResume = rerunOnResume
    }

    fun waitForOutput(waitForOutput: Boolean): Builder = apply {
      this.waitForOutput = waitForOutput
    }

    fun config(config: NodeConfig): Builder = apply { this.config = config }

    fun inputSchema(inputSchema: Schema?): Builder = apply { this.inputSchema = inputSchema }

    fun outputSchema(outputSchema: Schema?): Builder = apply { this.outputSchema = outputSchema }

    fun stateSchema(stateSchema: Schema?): Builder = apply { this.stateSchema = stateSchema }

    fun build(): ClassifierNode =
      ClassifierNode(
        name = checkNotNull(name) { "ClassifierNode.Builder requires name to be set." },
        classifier =
          checkNotNull(classifier) { "ClassifierNode.Builder requires classifier to be set." },
        question = checkNotNull(question) { "ClassifierNode.Builder requires question to be set." },
        threshold = threshold,
        answerKey = answerKey,
        description = description,
        rerunOnResume = rerunOnResume,
        waitForOutput = waitForOutput,
        config = config,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        stateSchema = stateSchema,
      )
  }

  companion object {
    /** The name the node asks its question under, as in Python ADK. */
    private const val QUESTION_NAME = "route"

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/** Routes as a sorted list in their JSON form, which tells the label `"true"` from `true`. */
private fun Set<Route>.labels(): List<String> =
  map { adkJson.encodeToString(Route.serializer(), it) }.sorted()

private val strictContentJson: Json = Json(adkJson) { ignoreUnknownKeys = false }

/**
 * Decodes a [Content] written as JSON by `FunctionNode` if it has at least one populated part.
 * Returns `null` for any other JSON object so maps such as `{}` or `{"role": "admin"}` remain JSON
 * inputs.
 */
private fun JsonObject.toContentOrNull(): Content? {
  val parts = this["parts"] as? JsonArray ?: return null
  if (parts.isEmpty() || parts.any { it !is JsonObject || it.isEmpty() }) return null
  return try {
    strictContentJson.decodeFromJsonElement(Content.serializer(), this)
  } catch (_: IllegalArgumentException) {
    null
  }
}

private fun toJsonObject(map: Map<*, *>, depth: Int): JsonObject {
  // Cap recursion before building the tree so deep or cyclic inputs cannot overflow the stack.
  checkContainerDepth(depth)
  return JsonObject(
    buildMap(map.size) {
      for ((key, value) in map.entries) {
        require(key is String) { "JSON content map keys must be strings, got ${typeName(key)}." }
        put(key, toJsonValue(value, depth + 1))
      }
    }
  )
}

private fun toJsonArray(list: List<*>, depth: Int): JsonArray {
  checkContainerDepth(depth)
  return JsonArray(list.map { toJsonValue(it, depth + 1) })
}

private fun toJsonValue(value: Any?, depth: Int): JsonElement =
  when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Long,
    is Int,
    is Short,
    is Byte,
    is Double,
    is Float -> JsonPrimitive(value)
    is Map<*, *> -> toJsonObject(value, depth)
    is List<*> -> toJsonArray(value, depth)
    else -> throw IllegalArgumentException("Unsupported value in JSON content: ${typeName(value)}.")
  }
