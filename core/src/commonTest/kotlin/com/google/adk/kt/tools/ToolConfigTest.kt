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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolConfigTest {

  @Test
  fun fromMap_nameOnly_hasNoArgs() {
    val config = ToolConfig.fromMap(mapOf("name" to "google_search"))

    assertEquals("google_search", config.name)
    assertNull(config.args)
  }

  @Test
  fun fromMap_emptyArgs_isNotTheSameAsNoArgs() {
    val config =
      ToolConfig.fromMap(mapOf("name" to "AgentTool", "args" to emptyMap<String, Any?>()))

    assertEquals(ToolArgsConfig(emptyMap()), config.args)
  }

  @Test
  fun fromMap_args_keepsValuesAsTheParserProducedThem() {
    val config =
      ToolConfig.fromMap(
        mapOf(
          "name" to "AgentTool",
          "args" to mapOf("agent" to "./another_agent.yaml", "skip_summarization" to true),
        )
      )

    assertEquals(
      mapOf<String, Any?>("agent" to "./another_agent.yaml", "skip_summarization" to true),
      config.args?.values,
    )
  }

  @Test
  fun fromMap_nullArgValue_isKept() {
    val config = ToolConfig.fromMap(mapOf("name" to "AgentTool", "args" to mapOf("agent" to null)))

    assertEquals(mapOf<String, Any?>("agent" to null), config.args?.values)
  }

  @Test
  fun fromMap_missingName_throws() {
    assertFailsWith<IllegalArgumentException> { ToolConfig.fromMap(emptyMap()) }
  }

  @Test
  fun fromMap_nonStringName_throws() {
    assertFailsWith<IllegalArgumentException> { ToolConfig.fromMap(mapOf("name" to 7)) }
  }

  @Test
  fun fromMap_undefinedKey_throwsAndNamesTheKey() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        ToolConfig.fromMap(mapOf("name" to "google_search", "arg" to mapOf("k" to "v")))
      }

    assertTrue(failure.message.orEmpty().contains("arg"))
  }

  @Test
  fun fromMap_argsNotAMapping_throws() {
    assertFailsWith<IllegalArgumentException> {
      ToolConfig.fromMap(mapOf("name" to "AgentTool", "args" to listOf("agent")))
    }
  }

  @Test
  fun fromMap_nonStringArgKey_throws() {
    assertFailsWith<IllegalArgumentException> {
      ToolConfig.fromMap(mapOf("name" to "AgentTool", "args" to mapOf(1 to "v")))
    }
  }

  @Test
  fun fromMap_failureMessages_doNotEchoValues() {
    val secret = "a-config-value-that-must-not-be-logged"

    val messages =
      listOf(
          mapOf("name" to "AgentTool", "args" to secret),
          mapOf("name" to "AgentTool", "args" to mapOf(1 to secret)),
          mapOf("name" to listOf(secret)),
        )
        .map { entry ->
          assertFailsWith<IllegalArgumentException> { ToolConfig.fromMap(entry) }.message.orEmpty()
        }

    for (message in messages) {
      assertFalse(message.contains(secret))
    }
  }
}
