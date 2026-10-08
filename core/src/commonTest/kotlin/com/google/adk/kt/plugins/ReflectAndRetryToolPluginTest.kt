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

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.ReflectAndRetryToolPlugin.TrackingScope
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class ReflectAndRetryToolPluginTest {

  private val tool = DummyTool(name = "test_tool")
  private val toolContext = testToolContext()
  private val args = mapOf("param1" to "value1", "param2" to 42, "param3" to true)

  @Test
  fun constructor_defaults() {
    val plugin = ReflectAndRetryToolPlugin()

    assertEquals("reflect_retry_tool_plugin", plugin.name)
    assertEquals(3, plugin.maxRetries)
    assertTrue(plugin.throwExceptionIfRetryExceeded)
    assertEquals(TrackingScope.INVOCATION, plugin.trackingScope)
    assertNull(plugin.resultErrorExtractor)
    assertNull(plugin.retryPredicate)
  }

  @Test
  fun constructor_negativeMaxRetries_throws() {
    val exception =
      assertFailsWith<IllegalArgumentException> { ReflectAndRetryToolPlugin(maxRetries = -1) }

    assertEquals("maxRetries must be a non-negative integer.", exception.message)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_setsEveryProperty() {
    val extractor = ReflectAndRetryToolPlugin.ResultErrorExtractor { _, _, _, _ -> null }
    val predicate = ReflectAndRetryToolPlugin.RetryPredicate { _, _, _, _ -> true }

    val plugin =
      ReflectAndRetryToolPlugin.builder()
        .maxRetries(5)
        .throwExceptionIfRetryExceeded(false)
        .trackingScope(TrackingScope.GLOBAL)
        .resultErrorExtractor(extractor)
        .retryPredicate(predicate)
        .name("custom_plugin")
        .build()

    assertEquals(5, plugin.maxRetries)
    assertFalse(plugin.throwExceptionIfRetryExceeded)
    assertEquals(TrackingScope.GLOBAL, plugin.trackingScope)
    assertSame(extractor, plugin.resultErrorExtractor)
    assertSame(predicate, plugin.retryPredicate)
    assertEquals("custom_plugin", plugin.name)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_unsetProperties_matchConstructorDefaults() {
    val built = ReflectAndRetryToolPlugin.builder().build()
    val constructed = ReflectAndRetryToolPlugin()

    assertEquals(constructed.maxRetries, built.maxRetries)
    assertEquals(constructed.throwExceptionIfRetryExceeded, built.throwExceptionIfRetryExceeded)
    assertEquals(constructed.trackingScope, built.trackingScope)
    assertEquals(constructed.resultErrorExtractor, built.resultErrorExtractor)
    assertEquals(constructed.retryPredicate, built.retryPredicate)
    assertEquals(constructed.name, built.name)
  }

  @Test
  fun onToolError_firstFailure_answersWithReflectionGuidance() = runBlocking {
    val response =
      ReflectAndRetryToolPlugin().answerError(IllegalArgumentException("Test error message"))

    assertEquals(
      mapOf(
        "response_type" to "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN",
        "error_type" to "IllegalArgumentException",
        "error_details" to "Test error message",
        "retry_count" to 1,
        "reflection_guidance" to EXPECTED_REFLECTION_GUIDANCE,
      ),
      response,
    )
  }

  @Test
  fun onToolError_consecutiveFailures_countUp() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(maxRetries = 5)

    val retryCounts = List(3) { plugin.answerError()["retry_count"] }

    assertEquals(listOf(1, 2, 3), retryCounts)
  }

  @Test
  fun onToolError_differentTools_countSeparately() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    plugin.recordFailure(tool = DummyTool(name = "tool1"))

    val response = plugin.answerError(tool = DummyTool(name = "tool2"))

    assertEquals(1, response["retry_count"])
  }

  @Test
  fun onToolError_retriesExceeded_leavesTheErrorToTheFlow() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(maxRetries = 1)
    val error = IllegalStateException("Connection failed")
    plugin.recordFailure(error)

    val choice = plugin.onToolError(toolContext, tool, args, error)

    assertEquals(CallbackChoice.Continue(Unit), choice)
  }

  @Test
  fun onToolError_retriesExceededWithoutThrowing_answersWithGiveUpGuidance() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(maxRetries = 2, throwExceptionIfRetryExceeded = false)
    val error = IllegalStateException("Timeout occurred")
    repeat(2) { plugin.recordFailure(error) }

    val response = plugin.answerError(error)

    assertEquals(
      mapOf(
        "response_type" to "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN",
        "error_type" to "IllegalStateException",
        "error_details" to "Timeout occurred",
        "retry_count" to 2,
        "reflection_guidance" to EXPECTED_RETRY_EXCEEDED_GUIDANCE,
      ),
      response,
    )
  }

  @Test
  fun onToolError_maxRetriesZero_leavesTheErrorToTheFlow() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(maxRetries = 0)

    val choice = plugin.onToolError(toolContext, tool, args, IllegalArgumentException("Test error"))

    assertEquals(CallbackChoice.Continue(Unit), choice)
  }

  @Test
  fun onToolError_maxRetriesZeroWithoutThrowing_answersWithGiveUpGuidance() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(maxRetries = 0, throwExceptionIfRetryExceeded = false)

    val response = plugin.answerError(IllegalArgumentException("Test error"))

    assertEquals("IllegalArgumentException", response["error_type"])
    assertEquals(0, response["retry_count"])
    assertContains(response.guidance, "the retry limit has been exceeded")
  }

  @Test
  fun onToolError_emptyArgs_writesEmptyJsonObject() = runBlocking {
    val response = ReflectAndRetryToolPlugin().answerError(args = mapOf())

    assertContains(response.guidance, "```json\n{}\n```")
  }

  @Test
  fun onToolError_argNotJsonNative_writesItsString() = runBlocking {
    val value =
      object {
        override fun toString() = "custom value"
      }

    val response =
      ReflectAndRetryToolPlugin().answerError(args = mapOf("key" to listOf(value, null)))

    assertContains(response.guidance, "{\n  \"key\": [\n    \"custom value\",\n    null\n  ]\n}")
  }

  @Test
  fun onToolError_setAndArrayArgs_writeJsonArrays() = runBlocking {
    val response =
      ReflectAndRetryToolPlugin()
        .answerError(args = mapOf("set" to setOf(1), "array" to arrayOf("a")))

    assertContains(
      response.guidance,
      "{\n  \"set\": [\n    1\n  ],\n  \"array\": [\n    \"a\"\n  ]\n}",
    )
  }

  @Test
  fun onToolError_nonFiniteArgs_writeThemLikePython() = runBlocking {
    val response =
      ReflectAndRetryToolPlugin()
        .answerError(args = mapOf("nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY))

    assertContains(response.guidance, "{\n  \"nan\": NaN,\n  \"inf\": Infinity\n}")
  }

  @Test
  fun afterTool_success_returnsResultUnchanged() = runBlocking {
    val result = mapOf("success" to true, "data" to "test_data")

    assertEquals(result, ReflectAndRetryToolPlugin().afterTool(toolContext, tool, args, result))
  }

  @Test
  fun afterTool_success_resetsOnlyThatToolsCount() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    val otherTool = DummyTool(name = "other_tool")
    plugin.recordFailure()
    plugin.recordFailure(tool = otherTool)

    plugin.recordSuccess()

    val retryCounts =
      listOf(plugin.answerError(), plugin.answerError(tool = otherTool)).map { it["retry_count"] }
    assertEquals(listOf(1, 2), retryCounts)
  }

  @Test
  fun afterTool_ownReflectionResponse_passesItThrough() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    val reflection = plugin.answerError()

    val passedThrough = plugin.afterTool(toolContext, tool, args, reflection)

    assertEquals(reflection, passedThrough)
  }

  @Test
  fun afterTool_ownReflectionResponse_keepsCount() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    val unused = plugin.afterTool(toolContext, tool, args, plugin.answerError())

    val retry = plugin.answerError()

    assertEquals(2, retry["retry_count"])
  }

  @Test
  fun afterTool_errorShapedResultWithoutExtractor_countsAsSuccess() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    plugin.recordFailure()
    val result = mapOf("status" to "error", "message" to "Something went wrong")

    val returned = plugin.afterTool(toolContext, tool, args, result)
    val retry = plugin.answerError()

    assertEquals(result, returned)
    assertEquals(1, retry["retry_count"])
  }

  @Test
  fun afterTool_extractedError_answersWithReflectionGuidance() = runBlocking {
    val result = mapOf("status" to "error", "message" to "Something went wrong")

    val response = statusErrorPlugin(maxRetries = 3).afterTool(toolContext, tool, args, result)

    assertEquals("ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN", response["response_type"])
    assertEquals("ToolError", response["error_type"])
    assertEquals(result, response["error_details"])
    assertEquals(1, response["retry_count"])
    assertContains(
      response.guidance,
      "**Error Details:**\n```\n{\n  \"status\": \"error\",\n  \"message\": \"Something went wrong\"\n}\n```",
    )
  }

  @Test
  fun afterTool_extractedTextError_reportsTheText() = runBlocking {
    val plugin =
      ReflectAndRetryToolPlugin(resultErrorExtractor = { _, _, _, result -> result["error"] })

    val response = plugin.afterTool(toolContext, tool, args, mapOf("error" to "Quota exceeded"))

    assertEquals("Quota exceeded", response["error_details"])
    assertContains(response.guidance, "**Error Details:**\n```\nQuota exceeded\n```")
  }

  @Test
  fun onToolError_afterExtractedError_sharesTheCount() = runBlocking {
    val plugin = statusErrorPlugin(maxRetries = 3)
    val errorResult = mapOf("status" to "error", "reason" to "Network timeout")
    val unused = plugin.afterTool(toolContext, tool, args, errorResult)

    val response = plugin.answerError(IllegalArgumentException("Invalid parameter"))

    assertEquals(2, response["retry_count"])
  }

  @Test
  fun afterTool_successAfterExtractedError_resetsTheCount() = runBlocking {
    val plugin = statusErrorPlugin(maxRetries = 3)
    val errorResult = mapOf("status" to "error", "reason" to "Network timeout")
    val unused = plugin.afterTool(toolContext, tool, args, errorResult)
    plugin.recordSuccess()

    val response = plugin.afterTool(toolContext, tool, args, errorResult)

    assertEquals(1, response["retry_count"])
  }

  @Test
  fun afterTool_extractedErrorRetriesExceeded_throwsWithoutTheResultInTheMessage() = runBlocking {
    val plugin = statusErrorPlugin(maxRetries = 1)
    val errorResult = mapOf("status" to "error", "message" to "Custom dict error")
    val unused = plugin.afterTool(toolContext, tool, args, errorResult)

    val thrown =
      assertFailsWith<IllegalStateException> {
        plugin.afterTool(toolContext, tool, args, errorResult)
      }

    assertEquals(
      "A tool result reported an error and the retry limit was exceeded.",
      thrown.message,
    )
  }

  @Test
  fun afterTool_extractedThrowableRetriesExceeded_rethrowsIt() = runBlocking {
    val error = IllegalStateException("reported by the result")
    val plugin =
      ReflectAndRetryToolPlugin(maxRetries = 0, resultErrorExtractor = { _, _, _, _ -> error })

    val thrown =
      assertFailsWith<IllegalStateException> { plugin.afterTool(toolContext, tool, args, mapOf()) }

    assertSame(error, thrown)
  }

  @Test
  fun onToolError_nonRetryableError_givesUpWithoutThrowing() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(retryPredicate = { _, _, _, _ -> false })

    val response = plugin.answerError(IllegalStateException("Permanent failure"))

    assertEquals("IllegalStateException", response["error_type"])
    assertEquals(0, response["retry_count"])
    assertEquals(
      "The tool `test_tool` failed with an error that retrying cannot fix.",
      response.guidance.lines().first(),
    )
    assertContains(response.guidance, "Do not attempt to use the `test_tool` tool again")
  }

  @Test
  fun onToolError_nonRetryableError_leavesTheCountAlone() = runBlocking {
    val plugin =
      ReflectAndRetryToolPlugin(
        retryPredicate = { _, _, _, error -> error !is IllegalArgumentException }
      )
    plugin.recordFailure()

    val giveUp = plugin.answerError(IllegalArgumentException("Bad request"))
    val retry = plugin.answerError()

    assertContains(giveUp.guidance, "retrying cannot fix")
    assertEquals(2, retry["retry_count"])
  }

  @Test
  fun afterTool_nonRetryableExtractedError_givesUpWithoutThrowing() = runBlocking {
    var predicateError: Any? = null
    val plugin =
      ReflectAndRetryToolPlugin(
        resultErrorExtractor = STATUS_ERROR,
        retryPredicate = { _, _, _, error ->
          predicateError = error
          false
        },
      )
    val result = mapOf("status" to "error", "code" to 404)

    val response = plugin.afterTool(toolContext, tool, args, result)

    assertEquals(result, predicateError)
    assertEquals("ToolError", response["error_type"])
    assertContains(response.guidance, "Do not attempt to use the `test_tool` tool again")
  }

  @Test
  fun invocationScope_countsEachInvocationSeparately() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    plugin.recordFailure(context = contextOf(invocationId = "inv-1"))

    val response = plugin.answerError(context = contextOf(invocationId = "inv-2"))

    assertEquals(1, response["retry_count"])
  }

  @Test
  fun globalScope_sharesCountAcrossInvocations() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(trackingScope = TrackingScope.GLOBAL)
    plugin.recordFailure(context = contextOf(invocationId = "inv-1"))

    val response = plugin.answerError(context = contextOf(invocationId = "inv-2"))

    assertEquals(2, response["retry_count"])
  }

  @Test
  fun afterRun_dropsOnlyThatInvocationsCounts() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    plugin.recordFailure(context = contextOf(invocationId = "inv-1"))
    plugin.recordFailure(context = contextOf(invocationId = "inv-2"))

    plugin.afterRun(testInvocationContext(invocationId = "inv-1"))

    val retryCounts =
      listOf("inv-1", "inv-2").map {
        plugin.answerError(context = contextOf(invocationId = it))["retry_count"]
      }
    assertEquals(listOf(1, 2), retryCounts)
  }

  @Test
  fun onRunError_dropsThatInvocationsCounts() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin()
    plugin.recordFailure(context = contextOf(invocationId = "inv-1"))

    plugin.onRunError(testInvocationContext(invocationId = "inv-1"), IllegalStateException("boom"))

    val response = plugin.answerError(context = contextOf(invocationId = "inv-1"))
    assertEquals(1, response["retry_count"])
  }

  @Test
  fun afterRun_globalScope_keepsTheCount() = runBlocking {
    val plugin = ReflectAndRetryToolPlugin(trackingScope = TrackingScope.GLOBAL)
    plugin.recordFailure(context = contextOf(invocationId = "inv-1"))

    plugin.afterRun(testInvocationContext(invocationId = "inv-1"))

    val response = plugin.answerError(context = contextOf(invocationId = "inv-2"))
    assertEquals(2, response["retry_count"])
  }

  @Test
  fun onToolError_concurrentFailures_eachGetADistinctCount() =
    runBlocking(Dispatchers.Default) {
      // A lost update occurs only in some runs, so repeat the race.
      repeat(50) {
        val plugin = ReflectAndRetryToolPlugin(maxRetries = 100)

        val retryCounts =
          List(100) { async { plugin.answerError()["retry_count"] as Int } }.awaitAll()

        assertEquals((1..100).toList(), retryCounts.sorted())
      }
    }

  @Test
  fun runAsync_unregisteredToolName_modelGetsGuidanceThenCallsTheRealTool() = runBlocking {
    var increaseCalls = 0
    val increase =
      DummyTool(name = "increase") { _, toolArgs ->
        increaseCalls++
        mapOf("result" to (toolArgs["x"] as Number).toInt() + 1)
      }
    val model =
      DummyModel.createSequential(
        "mock-model",
        listOf(
          modelFunctionCallResponse("increase_by_one", mapOf("x" to 1), id = "call_1"),
          modelFunctionCallResponse("increase", mapOf("x" to 1), id = "call_2"),
          LlmResponse(content = modelMessage("response1")),
        ),
      )
    val agent = LlmAgent(name = "root_agent", model = model, tools = listOf(increase))

    val events =
      InMemoryRunner(agent = agent, plugins = listOf(ReflectAndRetryToolPlugin()))
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("test"))
        .toList()

    assertEquals(
      listOf("increase_by_one", "increase"),
      events.flatMap { it.functionCalls() }.map { it.name },
    )
    val response = events.flatMap { it.functionResponses() }.first().response
    assertEquals("IllegalArgumentException", response["error_type"])
    assertEquals(1, response["retry_count"])
    assertContains(response.guidance, "Wrong Function Name")
    assertEquals(1, increaseCalls)
  }

  @Test
  fun runAsync_unregisteredToolNamePastTheLimit_modelGetsTheAvailableTools() = runBlocking {
    val model =
      DummyModel.createSequential(
        "mock-model",
        listOf(
          modelFunctionCallResponse("increase_by_one", mapOf("x" to 1), id = "call_1"),
          LlmResponse(content = modelMessage("Done.")),
        ),
      )
    val agent =
      LlmAgent(name = "root_agent", model = model, tools = listOf(DummyTool(name = "increase")))

    val events =
      InMemoryRunner(agent = agent, plugins = listOf(ReflectAndRetryToolPlugin(maxRetries = 0)))
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("test"))
        .toList()

    val response = events.flatMap { it.functionResponses() }.single().response
    assertContains(response["error"] as String, "The tools you can call are: increase.")
    assertEquals("Done.", events.last().content?.parts?.single()?.text)
  }

  @Test
  fun runAsync_toolFailsOnce_modelRetriesAfterGuidance() = runBlocking {
    var calls = 0
    val flakyTool =
      DummyTool(name = "flaky_tool") { _, _ ->
        if (++calls == 1) throw IllegalStateException("Temporary failure")
        mapOf("result" to "ok")
      }
    val model =
      DummyModel.createSequential(
        "mock-model",
        listOf(
          modelFunctionCallResponse("flaky_tool", id = "call_1"),
          modelFunctionCallResponse("flaky_tool", id = "call_2"),
          LlmResponse(content = modelMessage("Done.")),
        ),
      )
    val agent = LlmAgent(name = "root_agent", model = model, tools = listOf(flakyTool))

    val events =
      InMemoryRunner(agent = agent, plugins = listOf(ReflectAndRetryToolPlugin()))
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("test"))
        .toList()

    val responses = events.flatMap { it.functionResponses() }.map { it.response }
    assertEquals(2, responses.size)
    assertEquals("IllegalStateException", responses[0]["error_type"])
    assertEquals(mapOf("result" to "ok"), responses[1])
    assertEquals("Done.", events.last().content?.parts?.single()?.text)
  }

  @Test
  fun runAsync_extractedError_modelGetsTheErrorAsData() = runBlocking {
    var calls = 0
    val flakyTool =
      DummyTool(name = "flaky_tool") { _, _ ->
        if (++calls == 1) mapOf("status" to "error", "code" to "E42") else mapOf("status" to "ok")
      }
    val model =
      DummyModel.createSequential(
        "mock-model",
        listOf(
          modelFunctionCallResponse("flaky_tool", id = "call_1"),
          modelFunctionCallResponse("flaky_tool", id = "call_2"),
          LlmResponse(content = modelMessage("Done.")),
        ),
      )
    val agent = LlmAgent(name = "root_agent", model = model, tools = listOf(flakyTool))

    val events =
      InMemoryRunner(agent = agent, plugins = listOf(statusErrorPlugin(maxRetries = 3)))
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("test"))
        .toList()

    val responses = events.flatMap { it.functionResponses() }.map { it.response }
    assertEquals(mapOf("status" to "error", "code" to "E42"), responses[0]["error_details"])
    assertEquals(mapOf("status" to "ok"), responses[1])
    assertEquals("Done.", events.last().content?.parts?.single()?.text)
  }

  @Test
  fun runAsync_retriesExceeded_failsTheRunWithTheToolError() = runBlocking {
    val failingTool =
      DummyTool(name = "failing_tool") { _, _ -> throw IllegalStateException("Still failing") }
    val model =
      DummyModel.createSequential(
        "mock-model",
        listOf(
          modelFunctionCallResponse("failing_tool", id = "call_1"),
          modelFunctionCallResponse("failing_tool", id = "call_2"),
          LlmResponse(content = modelMessage("Unreachable.")),
        ),
      )
    val agent = LlmAgent(name = "root_agent", model = model, tools = listOf(failingTool))
    val runner =
      InMemoryRunner(agent = agent, plugins = listOf(ReflectAndRetryToolPlugin(maxRetries = 1)))

    val thrown =
      assertFailsWith<IllegalStateException> {
        runner
          .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("test"))
          .toList()
      }

    assertEquals("Still failing", thrown.message)
  }

  private suspend fun ReflectAndRetryToolPlugin.answerError(
    error: Throwable = IllegalStateException("boom"),
    tool: BaseTool = this@ReflectAndRetryToolPluginTest.tool,
    args: Map<String, Any?> = this@ReflectAndRetryToolPluginTest.args,
    context: ToolContext = toolContext,
  ): Map<String, Any?> =
    when (val choice = onToolError(context, tool, args, error)) {
      is CallbackChoice.Break -> choice.value
      is CallbackChoice.Continue -> fail("Expected the plugin to answer the tool error.")
    }

  private suspend fun ReflectAndRetryToolPlugin.recordFailure(
    error: Throwable = IllegalStateException("boom"),
    tool: BaseTool = this@ReflectAndRetryToolPluginTest.tool,
    context: ToolContext = toolContext,
  ) {
    val unused = answerError(error, tool = tool, context = context)
  }

  private suspend fun ReflectAndRetryToolPlugin.recordSuccess() {
    val unused = afterTool(toolContext, tool, args, mapOf("success" to true))
  }

  private fun contextOf(invocationId: String): ToolContext =
    testToolContext(testInvocationContext(invocationId = invocationId))

  private val Map<String, Any?>.guidance: String
    get() = this["reflection_guidance"] as String

  private fun statusErrorPlugin(maxRetries: Int) =
    ReflectAndRetryToolPlugin(maxRetries = maxRetries, resultErrorExtractor = STATUS_ERROR)

  companion object {
    /** Treats a result holding `"status": "error"` as a failure, like Python's sample plugin. */
    private val STATUS_ERROR = ReflectAndRetryToolPlugin.ResultErrorExtractor { _, _, _, result ->
      result.takeIf { it["status"] == "error" }
    }

    // Rendered from Python ADK's templates for the same tool, error and arguments.
    private val EXPECTED_REFLECTION_GUIDANCE =
      """
      The call to tool `test_tool` failed.

      **Error Details:**
      ```
      IllegalArgumentException: Test error message
      ```

      **Tool Arguments Used:**
      ```json
      {
        "param1": "value1",
        "param2": 42,
        "param3": true
      }
      ```

      **Reflection Guidance:**
      This is retry attempt **1 of 3**. Analyze the error and the arguments you provided. Do not repeat the exact same call. Consider the following before your next attempt:

      1.  **Invalid Parameters**: Does the error suggest that one or more arguments are incorrect, badly formatted, or missing? Review the tool's schema and your arguments.
      2.  **State or Preconditions**: Did a previous step fail or not produce the necessary state/resource for this tool to succeed?
      3.  **Alternative Approach**: Is this the right tool for the job? Could another tool or a different sequence of steps achieve the goal?
      4.  **Simplify the Task**: Can you break the problem down into smaller, simpler steps?
      5.  **Wrong Function Name**: Does the error indicates the tool is not found? Please check again and only use available tools.

      Formulate a new plan based on your analysis and try a corrected or different approach.
      """
        .trimIndent()

    private val EXPECTED_RETRY_EXCEEDED_GUIDANCE =
      """
      The tool `test_tool` has failed consecutively 2 times and the retry limit has been exceeded.

      **Last Error:**
      ```
      IllegalStateException: Timeout occurred
      ```

      **Last Arguments Used:**
      ```json
      {
        "param1": "value1",
        "param2": 42,
        "param3": true
      }
      ```

      **Final Instruction:**
      **Do not attempt to use the `test_tool` tool again for this task.** You must now try a different approach. Acknowledge the failure and devise a new strategy, potentially using other available tools or informing the user that the task cannot be completed.
      """
        .trimIndent()
  }
}
