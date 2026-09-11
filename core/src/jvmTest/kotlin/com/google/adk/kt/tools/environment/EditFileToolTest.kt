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

package com.google.adk.kt.tools.environment

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.testing.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for [EditFileTool], covering line-break tolerance. Uses `runBlocking` for real file I/O.
 */
@OptIn(ExperimentalEnvironmentApi::class)
class EditFileToolTest {

  private lateinit var root: Path
  private lateinit var env: LocalEnvironment

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("adk-edit-file-tool-test")
    env = LocalEnvironment(root.toString())
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun editFile_handlesLineBreaks_linuxFileWindowsSearch() = runBlocking {
    env
      .writeFile(testToolContext(), "test.txt", "line1\nline2\nline3".encodeToByteArray())
      .getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf(
            "path" to "test.txt",
            "old_string" to "line1\r\nline2",
            "new_string" to "line1_replaced\nline2_replaced",
          ),
        ) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals(
      "line1_replaced\nline2_replaced\nline3",
      env.readFile(testToolContext(), "test.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun editFile_handlesLineBreaks_windowsFileLinuxSearch() = runBlocking {
    env
      .writeFile(testToolContext(), "test.txt", "line1\r\nline2\r\nline3".encodeToByteArray())
      .getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf(
            "path" to "test.txt",
            "old_string" to "line1\nline2",
            "new_string" to "line1_replaced\r\nline2_replaced",
          ),
        ) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals(
      "line1_replaced\r\nline2_replaced\r\nline3",
      env.readFile(testToolContext(), "test.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun editFile_failsOnMultipleMatches() = runBlocking {
    env
      .writeFile(testToolContext(), "test.txt", "line1\nline2\nline1\nline2".encodeToByteArray())
      .getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "line1\nline2", "new_string" to "replaced"),
        ) as Map<*, *>

    assertEquals("error", result["status"])
    assertTrue((result["error"] as String).contains("appears 2 times"))
  }

  @Test
  fun editFile_emptyPath_returnsError() = runBlocking {
    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "", "old_string" to "line1", "new_string" to "replaced"),
        ) as Map<*, *>

    assertEquals("error", result["status"])
    assertEquals("`path` is required.", result["error"])
  }

  @Test
  fun editFile_emptyOldString_returnsError() = runBlocking {
    env.writeFile(testToolContext(), "test.txt", "line1".encodeToByteArray()).getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "", "new_string" to "replaced"),
        ) as Map<*, *>

    assertEquals("error", result["status"])
    assertTrue((result["error"] as String).contains("`old_string` cannot be empty"))
  }

  @Test
  fun editFile_exactMatchWorks() = runBlocking {
    env
      .writeFile(testToolContext(), "test.txt", "line1\nline2\nline3".encodeToByteArray())
      .getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "line1\nline2", "new_string" to "replaced"),
        ) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals(
      "replaced\nline3",
      env.readFile(testToolContext(), "test.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun editFile_handlesSpecialRegexChars() = runBlocking {
    env
      .writeFile(testToolContext(), "test.txt", "line1.content\nline2".encodeToByteArray())
      .getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "line1.content", "new_string" to "replaced"),
        ) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals(
      "replaced\nline2",
      env.readFile(testToolContext(), "test.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun editFile_treatsOldStringAsLiteralNotRegex() = runBlocking {
    // If `old_string` were used as an unescaped regex, `a.b` would also match `axb`, giving two
    // matches and a failure. Escaping means it matches only the literal `a.b`.
    env.writeFile(testToolContext(), "test.txt", "a.b axb".encodeToByteArray()).getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "a.b", "new_string" to "Z"),
        ) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals("Z axb", env.readFile(testToolContext(), "test.txt").getOrThrow().decodeToString())
  }

  @Test
  fun editFile_treatsNewStringAsLiteral() = runBlocking {
    // `$0` must be written verbatim, not expanded to the matched text as a regex replacement would.
    env.writeFile(testToolContext(), "test.txt", "foo".encodeToByteArray()).getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "foo", "new_string" to "\$0-bar"),
        ) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals(
      "\$0-bar",
      env.readFile(testToolContext(), "test.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun editFile_oldStringNotFound_returnsError() = runBlocking {
    env.writeFile(testToolContext(), "test.txt", "hello".encodeToByteArray()).getOrThrow()

    val result =
      EditFileTool(env)
        .run(
          testToolContext(),
          mapOf("path" to "test.txt", "old_string" to "world", "new_string" to "x"),
        ) as Map<*, *>

    assertEquals("error", result["status"])
    assertTrue((result["error"] as String).contains("not found"))
  }
}
