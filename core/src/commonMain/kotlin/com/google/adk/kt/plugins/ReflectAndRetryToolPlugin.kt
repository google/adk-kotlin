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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Answers a failed tool call with guidance for the model to reflect on the error and retry,
 * allowing up to [maxRetries] consecutive failures per tool.
 *
 * A failure is an exception from the tool, a call to an unregistered tool name, or an error that
 * [resultErrorExtractor] finds in a result; a success resets that tool's count. Mirrors Python
 * ADK's `ReflectAndRetryToolPlugin`.
 *
 * ```kotlin
 * InMemoryRunner(agent = agent, plugins = listOf(ReflectAndRetryToolPlugin(maxRetries = 3)))
 * ```
 *
 * @property maxRetries The number of consecutive failures of one tool that are answered with retry
 *   guidance; the failure after that exceeds the limit. Must not be negative; `0` disables retries.
 * @property throwExceptionIfRetryExceeded Once the limit is exceeded, whether to stop handling the
 *   error or to answer with guidance telling the model to stop using the tool. When set, a thrown
 *   error is left to the flow as if the plugin were absent, and an error found in a result is
 *   thrown.
 * @property trackingScope Whether a tool's failures are counted per invocation or across them.
 * @property resultErrorExtractor Finds errors that tools report in their results instead of
 *   throwing; when `null`, no result counts as a failure.
 * @property retryPredicate Decides which failures the model may retry. A failure it rejects is not
 *   counted and is answered at once with the guidance to stop using the tool, never by throwing;
 *   when `null`, every failure may be retried.
 * @property name The unique name of the plugin instance.
 */
class ReflectAndRetryToolPlugin(
  val maxRetries: Int = 3,
  val throwExceptionIfRetryExceeded: Boolean = true,
  val trackingScope: TrackingScope = TrackingScope.INVOCATION,
  val resultErrorExtractor: ResultErrorExtractor? = null,
  val retryPredicate: RetryPredicate? = null,
  override val name: String = DEFAULT_NAME,
) : Plugin {

  init {
    require(maxRetries >= 0) { "maxRetries must be a non-negative integer." }
  }

  private val failureCountsLock = Mutex()
  private val failureCounts = mutableMapOf<FailureCountKey, Int>()

  override suspend fun afterTool(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?> {
    // The plugin's guidance comes back here; counting it as a success would reset the budget.
    if (result[RESPONSE_TYPE_KEY] == REFLECT_AND_RETRY_RESPONSE_TYPE) return result
    val error = resultErrorExtractor?.extractErrorFromResult(context, tool, args, result)
    if (error != null) {
      handleToolError(context, tool, args, error)?.let {
        return it
      }
      // A non-Throwable error is tool output, so keep the message generic.
      throw error as? Throwable
        ?: IllegalStateException(
          "A tool result reported an error and the retry limit was exceeded."
        )
    }
    failureCountsLock.withLock { failureCounts.remove(failureCountKey(context, tool)) }
    return result
  }

  override suspend fun onToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any?>> =
    // Past the limit, leave the error to the flow: throwing here would log its message.
    handleToolError(context, tool, args, error)?.let { CallbackChoice.Break(it) }
      ?: CallbackChoice.Continue(Unit)

  override suspend fun afterRun(invocationContext: InvocationContext) =
    forgetInvocation(invocationContext.invocationId)

  override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) =
    forgetInvocation(invocationContext.invocationId)

  // Drop a completed or failed run's counts so they don't pile up; a resumed run starts over.
  private suspend fun forgetInvocation(invocationId: String) {
    failureCountsLock.withLock { failureCounts.keys.removeAll { it.invocationId == invocationId } }
  }

  private fun failureCountKey(context: ToolContext, tool: BaseTool): FailureCountKey =
    FailureCountKey(
      invocationId =
        when (trackingScope) {
          TrackingScope.INVOCATION -> context.invocationId
          TrackingScope.GLOBAL -> null
        },
      toolName = tool.name,
    )

  /**
   * Returns guidance answering [error], or `null` once the limit is exceeded and
   * [throwExceptionIfRetryExceeded] says to stop handling it.
   */
  private suspend fun handleToolError(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Any,
  ): Map<String, Any?>? {
    if (retryPredicate?.isRetryable(context, tool, args, error) == false) {
      return stopUsingToolResponse(
        tool,
        args,
        error,
        headline = "The tool `${tool.name}` failed with an error that retrying cannot fix.",
        retryCount = 0,
      )
    }
    if (maxRetries > 0) {
      val key = failureCountKey(context, tool)
      val retryCount = failureCountsLock.withLock {
        val count = (failureCounts[key] ?: 0) + 1
        failureCounts[key] = count
        count
      }
      if (retryCount <= maxRetries) return reflectionResponse(tool, args, error, retryCount)
    }
    if (throwExceptionIfRetryExceeded) return null
    return stopUsingToolResponse(
      tool,
      args,
      error,
      headline =
        "The tool `${tool.name}` has failed consecutively $maxRetries times and the retry limit " +
          "has been exceeded.",
      retryCount = maxRetries,
    )
  }

  private fun reflectionResponse(
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Any,
    retryCount: Int,
  ): Map<String, Any?> {
    val guidance =
      """
The call to tool `${tool.name}` failed.

**Error Details:**
```
${formatErrorDetails(error)}
```

**Tool Arguments Used:**
```json
${formatJson(args)}
```

**Reflection Guidance:**
This is retry attempt **$retryCount of $maxRetries**. Analyze the error and the arguments you provided. Do not repeat the exact same call. Consider the following before your next attempt:

1.  **Invalid Parameters**: Does the error suggest that one or more arguments are incorrect, badly formatted, or missing? Review the tool's schema and your arguments.
2.  **State or Preconditions**: Did a previous step fail or not produce the necessary state/resource for this tool to succeed?
3.  **Alternative Approach**: Is this the right tool for the job? Could another tool or a different sequence of steps achieve the goal?
4.  **Simplify the Task**: Can you break the problem down into smaller, simpler steps?
5.  **Wrong Function Name**: Does the error indicates the tool is not found? Please check again and only use available tools.

Formulate a new plan based on your analysis and try a corrected or different approach.
"""
        .trim()
    return toolFailureResponse(error, retryCount, guidance)
  }

  /** Tells the model to stop using [tool], after a [headline] that says why. */
  private fun stopUsingToolResponse(
    tool: BaseTool,
    args: Map<String, Any?>,
    error: Any,
    headline: String,
    retryCount: Int,
  ): Map<String, Any?> {
    val guidance =
      """
$headline

**Last Error:**
```
${formatErrorDetails(error)}
```

**Last Arguments Used:**
```json
${formatJson(args)}
```

**Final Instruction:**
**Do not attempt to use the `${tool.name}` tool again for this task.** You must now try a different approach. Acknowledge the failure and devise a new strategy, potentially using other available tools or informing the user that the task cannot be completed.
"""
        .trim()
    return toolFailureResponse(error, retryCount, guidance)
  }

  /** How long [ReflectAndRetryToolPlugin] keeps counting a tool's consecutive failures. */
  enum class TrackingScope {
    /** Counts failures within one invocation, so each new invocation starts from zero. */
    INVOCATION,

    /** Counts failures across every invocation, user and session that shares the plugin. */
    GLOBAL,
  }

  /** Finds an error that a tool reported in its result instead of throwing. */
  @JvmSuppressWildcards
  fun interface ResultErrorExtractor {
    /**
     * Returns the error in [result], such as [result] itself when it holds `"status": "error"`, or
     * `null` if the call succeeded.
     *
     * Past the limit, if [ReflectAndRetryToolPlugin.throwExceptionIfRetryExceeded] is set, the
     * plugin throws a [Throwable] error as is (logged with its message) and any other error as an
     * [IllegalStateException] with a generic message. A [Map] error reaches the model as data, not
     * text, so like a tool result it must hold only JSON-native values.
     */
    fun extractErrorFromResult(
      context: ToolContext,
      tool: BaseTool,
      args: Map<String, Any?>,
      result: Map<String, Any?>,
    ): Any?
  }

  /** Decides whether the model may retry a failed tool call. */
  @JvmSuppressWildcards
  fun interface RetryPredicate {
    /**
     * Returns `false` if retrying cannot fix [error], such as a permanent client error.
     *
     * [error] is the exception the tool threw or the error found in its result.
     */
    fun isRetryable(
      context: ToolContext,
      tool: BaseTool,
      args: Map<String, Any?>,
      error: Any,
    ): Boolean
  }

  private data class FailureCountKey(val invocationId: String?, val toolName: String)

  /**
   * Fluent builder for [ReflectAndRetryToolPlugin], provided primarily for Java callers. Any
   * property left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var maxRetries: Int = 3
    private var throwExceptionIfRetryExceeded: Boolean = true
    private var trackingScope: TrackingScope = TrackingScope.INVOCATION
    private var resultErrorExtractor: ResultErrorExtractor? = null
    private var retryPredicate: RetryPredicate? = null
    private var name: String = DEFAULT_NAME

    fun maxRetries(maxRetries: Int): Builder = apply { this.maxRetries = maxRetries }

    fun throwExceptionIfRetryExceeded(throwExceptionIfRetryExceeded: Boolean): Builder = apply {
      this.throwExceptionIfRetryExceeded = throwExceptionIfRetryExceeded
    }

    fun trackingScope(trackingScope: TrackingScope): Builder = apply {
      this.trackingScope = trackingScope
    }

    fun resultErrorExtractor(resultErrorExtractor: ResultErrorExtractor?): Builder = apply {
      this.resultErrorExtractor = resultErrorExtractor
    }

    fun retryPredicate(retryPredicate: RetryPredicate?): Builder = apply {
      this.retryPredicate = retryPredicate
    }

    fun name(name: String): Builder = apply { this.name = name }

    fun build(): ReflectAndRetryToolPlugin =
      ReflectAndRetryToolPlugin(
        maxRetries = maxRetries,
        throwExceptionIfRetryExceeded = throwExceptionIfRetryExceeded,
        trackingScope = trackingScope,
        resultErrorExtractor = resultErrorExtractor,
        retryPredicate = retryPredicate,
        name = name,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    private const val DEFAULT_NAME = "reflect_retry_tool_plugin"
    private const val RESPONSE_TYPE_KEY = "response_type"
    private const val REFLECT_AND_RETRY_RESPONSE_TYPE = "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN"

    private val PRETTY_JSON = Json {
      prettyPrint = true
      prettyPrintIndent = "  "
      allowSpecialFloatingPointValues = true
    }

    private fun toolFailureResponse(
      error: Any,
      retryCount: Int,
      reflectionGuidance: String,
    ): Map<String, Any?> =
      mapOf(
        RESPONSE_TYPE_KEY to REFLECT_AND_RETRY_RESPONSE_TYPE,
        "error_type" to errorType(error),
        "error_details" to errorDetails(error),
        "retry_count" to retryCount,
        "reflection_guidance" to reflectionGuidance,
      )

    private fun errorType(error: Any): String =
      if (error is Throwable) error::class.simpleName ?: "Exception" else "ToolError"

    private fun errorDetails(error: Any): Any =
      when (error) {
        is Throwable -> error.message.orEmpty()
        // Pass a structured error to the model as data, like a tool result.
        is Map<*, *> -> error
        else -> error.toString()
      }

    private fun formatErrorDetails(error: Any): String =
      when (error) {
        is Throwable -> "${errorType(error)}: ${error.message.orEmpty()}"
        is Map<*, *> -> formatJson(error)
        else -> error.toString()
      }

    /**
     * Formats [value] like Python's `json.dumps(value, indent=2, default=str)`, except any
     * `Iterable` or `Array` becomes a JSON array and non-ASCII text stays unescaped.
     */
    private fun formatJson(value: Any?): String =
      PRETTY_JSON.encodeToString(JsonElement.serializer(), toJsonElement(value))

    private fun toJsonElement(value: Any?): JsonElement =
      when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> ->
          JsonObject(
            value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) }
          )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        is Array<*> -> JsonArray(value.map(::toJsonElement))
        else -> JsonPrimitive(value.toString())
      }
  }
}
