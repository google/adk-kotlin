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

package com.google.adk.kt.tools.mcp.it

import com.google.adk.kt.tools.mcp.McpConnectionParameters
import com.google.adk.kt.tools.mcp.McpToolset
import com.google.adk.kt.tools.mcp.McpToolset.McpToolsetConfig
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.jvm.optionals.getOrNull
import org.junit.Assume

/**
 * Shared helpers and constants for the MCP **integration** tests (this `it` package), which launch
 * the real [FakeMcpServer] as a child JVM and talk to it over real stdio.
 */

/**
 * Env var that, when truthy, skips the MCP integration suites (e.g. sandboxes forbidding
 * subprocesses).
 */
const val DISABLE_IT_ENV = "ADK_MCP_DISABLE_IT"

/** Default per-call request timeout; generous to absorb cold child-JVM startup on CI. */
val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * A fixed, non-semantic token injected into the server's environment and reflected back by the
 * `whoami` tool and `mem://greeting` resource.
 */
const val INJECTED_TOKEN = "injected-token-probe"

/** Values treated as truthy for the `*_DISABLE_IT` gates. */
private val TRUTHY_VALUES = setOf("true", "t", "yes", "y", "1")

/**
 * Reads an environment variable, treating a blank value the same as unset (returns `null`). GitHub
 * Actions sets a configured env var to the empty string when the secret/variable it maps to does
 * not exist, so a plain null check is not enough -- such values must fall back to defaults.
 */
fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

/** True iff the named environment variable is set to a recognized truthy value. */
fun isEnvTruthy(name: String): Boolean = TRUTHY_VALUES.contains(envOrNull(name)?.lowercase())

/** Skips the calling suite when [DISABLE_IT_ENV] is truthy. */
fun assumeMcpItEnabled() {
  Assume.assumeFalse(
    "MCP integration tests disabled via $DISABLE_IT_ENV",
    isEnvTruthy(DISABLE_IT_ENV),
  )
}

/** This JVM's own `java` launcher, reused to spawn the fake server as a child process. */
fun javaBinary(): String = Path.of(System.getProperty("java.home")!!, "bin", "java").toString()

/**
 * Builds the [ServerParameters] that launch [FakeMcpServer] as a child JVM.
 *
 * We reuse this test JVM's own `java` binary and classpath, which already contain the fake server
 * class and the MCP SDK (the SDK is a `jvmMain` dependency, visible on the test runtime classpath).
 * When [pidFile] / [pidDir] are non-null, the server is told to record its PID there so a test can
 * find and kill the child process(es).
 */
fun fakeServerParameters(
  token: String = INJECTED_TOKEN,
  pidFile: Path? = null,
  pidDir: Path? = null,
): ServerParameters {
  val builder =
    ServerParameters.builder(javaBinary())
      .args("-cp", System.getProperty("java.class.path"), FakeMcpServer.MAIN_CLASS)
      .addEnvVar(FakeMcpServer.TOKEN_ENV, token)
  if (pidFile != null) {
    builder.addEnvVar(FakeMcpServer.PID_FILE_ENV, pidFile.toString())
  }
  if (pidDir != null) {
    builder.addEnvVar(FakeMcpServer.PID_DIR_ENV, pidDir.toString())
  }
  return builder.build()
}

/**
 * Builds an [McpToolset] wired to a freshly-spawned [FakeMcpServer] subprocess.
 *
 * TODO(b/529753915): `progressConsumers` is plumbed but intentionally unexercised. ADK's
 *   McpTool.run never sets a progressToken on outgoing tool calls, so a spec-conformant server
 *   emits no progress and the consumer never fires. Add a conformant progress test once that gap is
 *   closed.
 */
fun newToolset(
  token: String = INJECTED_TOKEN,
  useMcpResources: Boolean = false,
  pidFile: Path? = null,
  pidDir: Path? = null,
  requestTimeout: Duration = REQUEST_TIMEOUT,
  progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
): McpToolset =
  McpToolsetConfig(
      stdioConnectionParams =
        McpConnectionParameters.Stdio(
          serverParameters = fakeServerParameters(token, pidFile, pidDir),
          timeoutDuration = requestTimeout,
        ),
      useMcpResources = useMcpResources,
    )
    .toToolset(progressConsumers = progressConsumers)

/**
 * The JSON-native map `McpTool.run` now produces from a `CallToolResult` (see
 * `McpTool.toJsonNativeMap`); the SDK mapper renders it as `{"content": [{"type": ..., ...}],
 * ...}`.
 */
@Suppress("UNCHECKED_CAST")
fun resultMap(toolResult: Any): Map<String, Any?> = toolResult as Map<String, Any?>

/** Extracts the text of a single text-content result returned by `McpTool.run`. */
fun textOf(toolResult: Any): String =
  ((resultMap(toolResult)["content"] as List<*>).single() as Map<*, *>)["text"] as String

/** Whether the result returned by `McpTool.run` carries `isError: true`. */
fun isErrorOf(toolResult: Any): Boolean = resultMap(toolResult)["isError"] == true

/** Reads the PID the fake server wrote to [pidFile] (see [FakeMcpServer.PID_FILE_ENV]). */
fun readPid(pidFile: Path): Long = Files.readString(pidFile).trim().toLong()

/** Best-effort kill of whatever process last recorded its PID in [pidFile]; never throws. */
fun killIfRunning(pidFile: Path) {
  runCatching { ProcessHandle.of(readPid(pidFile)).ifPresent { it.destroyForcibly() } }
}

/** Every PID the fake server recorded under [pidDir] (one marker file per spawned process). */
fun recordedPids(pidDir: Path): List<Long> =
  Files.list(pidDir).use { stream ->
    stream.toList().mapNotNull { it.fileName?.toString()?.toLongOrNull() }
  }

/** Of the PIDs recorded under [pidDir], the processes still alive. */
fun liveRecordedProcesses(pidDir: Path): List<ProcessHandle> =
  recordedPids(pidDir).mapNotNull { ProcessHandle.of(it).getOrNull() }.filter { it.isAlive }

/** Polls until no recorded process is alive (or [maxSeconds] elapses); returns the survivors. */
fun awaitRecordedProcessesSettle(pidDir: Path, maxSeconds: Long): List<ProcessHandle> {
  val deadlineNanos = System.nanoTime() + Duration.ofSeconds(maxSeconds).toNanos()
  var live = liveRecordedProcesses(pidDir)
  while (live.isNotEmpty() && System.nanoTime() < deadlineNanos) {
    Thread.sleep(50)
    live = liveRecordedProcesses(pidDir)
  }
  return live
}

/** True if this throwable, or anything in its cause chain, is a [TimeoutException]. */
fun Throwable.causedByTimeout(): Boolean =
  generateSequence<Throwable>(this) { it.cause }.any { it is TimeoutException }
