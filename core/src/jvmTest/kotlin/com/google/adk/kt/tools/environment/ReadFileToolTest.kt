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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.environment.EnvironmentException
import com.google.adk.kt.environment.ExecutionResult
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.testing.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/** Tests for [ReadFileTool]. */
@OptIn(ExperimentalEnvironmentApi::class)
class ReadFileToolTest {

  /** Minimal environment double that fails the test if the tool shells out. */
  private class StubEnvironment(private val files: Map<String, ByteArray>) : Environment {
    override suspend fun execute(
      context: Context,
      command: String,
      timeout: Duration?,
    ): Result<ExecutionResult> = throw AssertionError("ReadFileTool should not invoke execute().")

    override suspend fun readFile(context: Context, path: String): Result<ByteArray> =
      files[path]?.let { Result.success(it) }
        ?: Result.failure(EnvironmentException("File not found."))

    override suspend fun writeFile(
      context: Context,
      path: String,
      content: ByteArray,
    ): Result<Unit> = throw NotImplementedError()
  }

  private lateinit var root: Path
  private lateinit var env: LocalEnvironment

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("adk-read-file-tool-test")
    env = LocalEnvironment(root.toString())
    runBlocking { env.initialize() }
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun readFileWithLineRange_usesDirectFileRead() = runBlocking {
    val stub =
      StubEnvironment(mapOf("notes.txt" to "alpha\nbeta\ngamma\ndelta\n".encodeToByteArray()))

    val result =
      ReadFileTool(stub, 30_000)
        .run(testToolContext(), mapOf("path" to "notes.txt", "start_line" to 2, "end_line" to 3))
        as Map<*, *>

    assertEquals(
      mapOf("status" to "ok", "content" to "     2\tbeta\n     3\tgamma\n", "total_lines" to 4),
      result,
    )
  }

  @Test
  fun readFileWithLineRange_treatsShellPayloadAsLiteralPath() = runBlocking {
    val stub = StubEnvironment(mapOf("safe.txt" to "line1\nline2\n".encodeToByteArray()))
    val payload = "'; python3 -c \"open('pwned.txt','w').write('owned')\"; echo '"

    val result =
      ReadFileTool(stub, 30_000)
        .run(testToolContext(), mapOf("path" to payload, "start_line" to 1, "end_line" to 2))
        as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "File not found."), result)
  }

  @Test
  fun readFileWithLineRange_returnsSelectedLines() = runBlocking {
    env
      .writeFile(testToolContext(), "sample.txt", "line1\nline2\nline3\n".encodeToByteArray())
      .getOrThrow()

    val result =
      ReadFileTool(env, 30_000)
        .run(testToolContext(), mapOf("path" to "sample.txt", "start_line" to 2, "end_line" to 3))
        as Map<*, *>

    assertEquals(
      mapOf("status" to "ok", "content" to "     2\tline2\n     3\tline3\n", "total_lines" to 3),
      result,
    )
  }

  @Test
  fun readFile_endLineZero_returnsWholeFile() = runBlocking {
    env
      .writeFile(testToolContext(), "sample.txt", "line1\nline2\nline3\n".encodeToByteArray())
      .getOrThrow()

    val result =
      ReadFileTool(env, 30_000)
        .run(testToolContext(), mapOf("path" to "sample.txt", "end_line" to 0)) as Map<*, *>

    assertEquals(
      mapOf("status" to "ok", "content" to "     1\tline1\n     2\tline2\n     3\tline3\n"),
      result,
    )
  }

  @Test
  fun readFile_clampsLongLineNumbersToIntRange() = runBlocking {
    val stub = StubEnvironment(mapOf("sample.txt" to "line1\nline2\nline3\n".encodeToByteArray()))
    val tool = ReadFileTool(stub, 30_000)

    // Narrowed with toInt(), 2^32 + 2 would wrap to 2 and Long.MAX_VALUE to -1.
    val pastEnd =
      tool.run(testToolContext(), mapOf("path" to "sample.txt", "start_line" to 4_294_967_298L))
        as Map<*, *>
    val wholeFile =
      tool.run(testToolContext(), mapOf("path" to "sample.txt", "end_line" to Long.MAX_VALUE))
        as Map<*, *>

    assertEquals(
      mapOf(
        "status" to "error",
        "error" to "`start_line` 2147483647 exceeds file length (3 lines).",
        "total_lines" to 3,
      ),
      pastEnd,
    )
    assertEquals(
      mapOf("status" to "ok", "content" to "     1\tline1\n     2\tline2\n     3\tline3\n"),
      wholeFile,
    )
  }

  @Test
  fun readFile_lastLineWithoutNewline_isNotTerminated() = runBlocking {
    val stub = StubEnvironment(mapOf("f.txt" to "a\nb".encodeToByteArray()))

    val result = ReadFileTool(stub, 30_000).run(testToolContext(), mapOf("path" to "f.txt"))

    // A trailing newline that is not in the file would break an EditFile copied from this output.
    assertEquals(mapOf("status" to "ok", "content" to "     1\ta\n     2\tb"), result)
  }

  @Test
  fun readFile_splitsOnBareCarriageReturn() = runBlocking {
    // A lone `\r` is a line boundary; endings are normalized to `\n` in the returned content.
    env.writeFile(testToolContext(), "mac.txt", "a\rb\rc".encodeToByteArray()).getOrThrow()

    val result =
      ReadFileTool(env, 30_000).run(testToolContext(), mapOf("path" to "mac.txt")) as Map<*, *>

    assertEquals(mapOf("status" to "ok", "content" to "     1\ta\n     2\tb\n     3\tc"), result)
  }

  @Test
  fun readFile_emptyFile_reportsZeroLines() = runBlocking {
    env.writeFile(testToolContext(), "empty.txt", ByteArray(0)).getOrThrow()

    val result =
      ReadFileTool(env, 30_000).run(testToolContext(), mapOf("path" to "empty.txt")) as Map<*, *>

    // An empty file has no lines at all, rather than a single empty one.
    assertEquals(
      mapOf(
        "status" to "error",
        "error" to "`start_line` 1 exceeds file length (0 lines).",
        "total_lines" to 0,
      ),
      result,
    )
  }

  @Test
  fun readFile_missingFile_returnsError() = runBlocking {
    val result =
      ReadFileTool(env, 30_000)
        .run(testToolContext(), mapOf("path" to "missing.txt", "start_line" to 2)) as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "File not found."), result)
  }

  @Test
  fun readFile_emptyPath_returnsError() = runBlocking {
    val result = ReadFileTool(env, 30_000).run(testToolContext(), mapOf("path" to "")) as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "`path` is required."), result)
  }

  @Test
  fun readFile_rejectsNonIntegerEndLine() = runBlocking {
    env
      .writeFile(testToolContext(), "sample.txt", "line1\nline2\n".encodeToByteArray())
      .getOrThrow()

    val result =
      ReadFileTool(env, 30_000)
        .run(
          testToolContext(),
          mapOf("path" to "sample.txt", "end_line" to "1'; touch marker; echo '"),
        ) as Map<*, *>

    assertEquals(
      mapOf("status" to "error", "error" to "`end_line` must be an integer if provided."),
      result,
    )
  }

  @Test
  fun readFile_rejectsBooleanLineNumbers() = runBlocking {
    env
      .writeFile(testToolContext(), "sample.txt", "line1\nline2\n".encodeToByteArray())
      .getOrThrow()

    val resStart =
      ReadFileTool(env, 30_000)
        .run(testToolContext(), mapOf("path" to "sample.txt", "start_line" to true)) as Map<*, *>
    val resEnd =
      ReadFileTool(env, 30_000)
        .run(testToolContext(), mapOf("path" to "sample.txt", "end_line" to false)) as Map<*, *>

    assertEquals(
      mapOf("status" to "error", "error" to "`start_line` must be an integer if provided."),
      resStart,
    )
    assertEquals(
      mapOf("status" to "error", "error" to "`end_line` must be an integer if provided."),
      resEnd,
    )
  }
}
