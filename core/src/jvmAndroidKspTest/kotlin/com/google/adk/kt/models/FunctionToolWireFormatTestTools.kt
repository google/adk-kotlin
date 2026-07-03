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

package com.google.adk.kt.models

import com.google.adk.kt.annotations.Tool

// `@Tool` fixtures shared by the `jvmAndroidKspTest` tests; the KSP `FunctionToolGenerator` emits a
// `<Name>Tool` `FunctionTool` subclass for each, built in a per-backend library.

@Tool fun returnsInt(): Int = 42

@Tool fun returnsString(): String = "hello"

@Tool fun returnsBoolean(): Boolean = true

@Tool fun returnsDouble(): Double = 3.14

@Tool fun returnsListOfInts(): List<Int> = listOf(1, 2, 3)

@Tool fun returnsScoreboard(): Map<String, Int> = mapOf("alice" to 100, "bob" to 87, "carol" to 42)

@Tool fun returnsUnit() {}

// A long-running tool returning `Unit` ("no response yet"), e.g. an HITL tool awaiting input.
// Drives KspLongRunningToolIntegrationTest: the generated `execute` returns the `Unit` singleton,
// which the framework treats as a signal to suppress the function-response event.
@Tool(isLongRunning = true) fun longRunningReturnsUnit() {}

@Tool fun requiresName(name: String): String = "hello, $name"

@Tool
fun returnsStockPrice(): Map<String, Any> =
  mapOf("symbol" to "GOOG", "price" to 123.45, "volume" to 1000)

@Tool fun returnsStockHistory(): List<Any> = listOf("GOOG", 123.45, 1000)

@Tool
fun returnsNestedData(): Map<String, Any> =
  mapOf(
    "outer" to
      mapOf(
        "middle" to
          listOf(mapOf("leaf" to 1, "label" to "first"), mapOf("leaf" to 2, "label" to "second")),
        "scalar" to "value",
      )
  )

@Tool
fun returnsKitchenSink(): Map<String, Any?> =
  mapOf(
    "name" to "the answer",
    "value" to 42,
    "ratio" to 0.5,
    "active" to true,
    "label" to null,
    "status" to "READY",
    "tags" to listOf("alpha", "beta"),
    "items" to listOf(mapOf("id" to 1, "label" to "first"), mapOf("id" to 2, "label" to "second")),
    "scores" to mapOf("alice" to 100, "bob" to 87),
  )
