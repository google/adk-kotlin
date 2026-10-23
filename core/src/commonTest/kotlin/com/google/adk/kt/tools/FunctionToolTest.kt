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

package com.google.adk.kt.tools

import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.testing.testToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FunctionToolTest {

  private class TestFunctionTool : FunctionTool("test_func", "A test function tool") {
    override fun declaration() = null

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
      return args
    }
  }

  @Test
  fun isLongRunning_default_isFalse() {
    val tool = TestFunctionTool()

    val result = tool.isLongRunning

    assertFalse(result)
  }

  @Test
  fun run_delegatesToExecute() = runTest {
    val tool = TestFunctionTool()
    val toolContext = testToolContext()
    val args = mapOf<String, Any>("arg1" to "value1")

    val result = tool.run(toolContext, args)

    assertEquals(args, result)
  }

  @Test
  fun run_requiresConfirmation_noConfirmationYet_requestsItAndDoesNotInvokeExecute() = runTest {
    var executed = false
    val tool = confirmGatedTool(name = "secure", onExecute = { executed = true })
    val toolContext = testToolContext(functionCallId = "fc1")

    val result = tool.run(toolContext, mapOf("amount" to 100))

    assertFalse(executed)
    assertEquals(mapOf(FunctionTool.ERROR_KEY to FunctionTool.CONFIRMATION_REQUIRED_ERROR), result)
    val pending = toolContext.actions.requestedToolConfirmations["fc1"]
    assertNotNull(pending)
    assertFalse(pending.confirmed)
    assertNull(pending.payload)
    assertTrue(toolContext.actions.skipSummarization)
  }

  @Test
  fun run_requiresConfirmation_userConfirmed_invokesExecute() = runTest {
    var executedArgs: Map<String, Any?>? = null
    val tool =
      confirmGatedTool(
        name = "secure",
        onExecute = { args -> executedArgs = args },
        result = mapOf("status" to "ok"),
      )
    val toolContext =
      testToolContext(functionCallId = "fc1", toolConfirmation = ToolConfirmation(confirmed = true))

    val result = tool.run(toolContext, mapOf("amount" to 100))

    assertEquals(mapOf("amount" to 100), executedArgs)
    assertEquals(mapOf("status" to "ok"), result)
    assertTrue(toolContext.actions.requestedToolConfirmations.isEmpty())
    assertFalse(toolContext.actions.skipSummarization)
  }

  @Test
  fun run_requiresConfirmation_userRejected_returnsRejectionAndDoesNotInvokeExecute() = runTest {
    var executed = false
    val tool = confirmGatedTool(name = "secure", onExecute = { executed = true })
    val toolContext =
      testToolContext(
        functionCallId = "fc1",
        toolConfirmation = ToolConfirmation(confirmed = false),
      )

    val result = tool.run(toolContext, mapOf("amount" to 100))

    assertFalse(executed)
    assertEquals(mapOf(FunctionTool.ERROR_KEY to FunctionTool.REJECTED_ERROR), result)
    assertTrue(toolContext.actions.requestedToolConfirmations.isEmpty())
  }

  @Test
  fun run_requiresConfirmationFalse_alwaysInvokesExecute() = runTest {
    var executed = false
    val tool =
      object : FunctionTool("plain", "plain", requiresConfirmation = false) {
        override fun declaration() = null

        override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
          executed = true
          return args
        }
      }
    val toolContext = testToolContext(functionCallId = "fc1")

    val unused = tool.run(toolContext, emptyMap())

    assertTrue(executed)
    assertTrue(toolContext.actions.requestedToolConfirmations.isEmpty())
  }

  @Test
  fun run_requiresConfirmationLambdaTrue_pausesAndDoesNotInvokeExecute() = runTest {
    // Per-call predicate form: a lambda that always returns true behaves identically to the
    // Boolean `true` form. The first invocation must record a confirmation request and skip
    // execute().
    var executed = false
    val tool = predicateGatedTool(predicate = { true }, onExecute = { executed = true })
    val toolContext = testToolContext(functionCallId = "fc1")

    val result = tool.run(toolContext, mapOf("amount" to 100))

    assertFalse(executed)
    assertEquals(mapOf(FunctionTool.ERROR_KEY to FunctionTool.CONFIRMATION_REQUIRED_ERROR), result)
    assertNotNull(toolContext.actions.requestedToolConfirmations["fc1"])
  }

  @Test
  fun run_requiresConfirmationLambdaFalse_invokesExecuteWithoutGate() = runTest {
    // Per-call predicate form: a lambda that returns false skips the gate entirely; execute()
    // runs without the framework recording a confirmation request.
    var executedArgs: Map<String, Any?>? = null
    val tool =
      predicateGatedTool(predicate = { false }, onExecute = { args -> executedArgs = args })
    val toolContext = testToolContext(functionCallId = "fc1")

    val unused = tool.run(toolContext, mapOf("amount" to 100))

    assertEquals(mapOf("amount" to 100), executedArgs)
    assertTrue(toolContext.actions.requestedToolConfirmations.isEmpty())
  }

  @Test
  fun run_requiresConfirmationLambdaConditionalOnArgs_gatesOnlyMatchingCalls() = runTest {
    // Per-call predicate form: the predicate receives the function's args and decides per
    // invocation whether to gate this specific call.
    var executions = 0
    val tool =
      predicateGatedTool(
        predicate = { args -> (args["amount"] as Int) > 1000 },
        onExecute = { executions++ },
      )

    // Below the threshold: no confirmation needed, execute runs.
    val smallContext = testToolContext(functionCallId = "fc-small")
    val unused1 = tool.run(smallContext, mapOf("amount" to 100))
    assertEquals(1, executions)
    assertTrue(smallContext.actions.requestedToolConfirmations.isEmpty())

    // Above the threshold: confirmation requested, execute does NOT run.
    val largeContext = testToolContext(functionCallId = "fc-large")
    val largeResult = tool.run(largeContext, mapOf("amount" to 5000))
    assertEquals(1, executions)
    assertEquals(
      mapOf(FunctionTool.ERROR_KEY to FunctionTool.CONFIRMATION_REQUIRED_ERROR),
      largeResult,
    )
    assertNotNull(largeContext.actions.requestedToolConfirmations["fc-large"])
  }

  /**
   * Builds a [FunctionTool] using the per-call predicate constructor form. The [predicate] decides
   * per invocation whether to gate; the [onExecute] callback runs only when the gate is open and
   * (if applicable) confirmed.
   */
  private fun predicateGatedTool(
    predicate: (Map<String, Any?>) -> Boolean,
    onExecute: (Map<String, Any?>) -> Unit = {},
    result: Map<String, Any> = mapOf("ok" to true),
  ): FunctionTool =
    object : FunctionTool("predicate-gated", "test", requiresConfirmation = predicate) {
      override fun declaration() = null

      override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        onExecute(args)
        return result
      }
    }

  /**
   * Builds a [FunctionTool] with `requiresConfirmation = true` whose [execute] body invokes
   * [onExecute] and returns [result].
   */
  private fun confirmGatedTool(
    name: String,
    onExecute: (Map<String, Any?>) -> Unit = {},
    result: Map<String, Any> = mapOf("ok" to true),
  ): FunctionTool =
    object : FunctionTool(name, "test", requiresConfirmation = true) {
      override fun declaration() = null

      override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        onExecute(args)
        return result
      }
    }
}
