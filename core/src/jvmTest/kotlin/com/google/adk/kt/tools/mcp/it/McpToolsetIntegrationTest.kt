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

import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.mcp.McpToolset
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end integration test for `McpToolset` over the **stdio** transport.
 *
 * The transport-agnostic behavior lives in [McpToolsetContract]; this suite supplies a stdio
 * [McpToolsetHarness] (the real [FakeMcpServer] launched as a child JVM, talking over actual
 * stdin/stdout pipes) and delegates one `@Test` to each contract check. On top of that it adds the
 * tests that only make sense for a real subprocess: recovery after the server process is killed,
 * behavior against an unresponsive server, and orphan-free teardown -- none of which the in-process
 * HTTP suite can exercise.
 *
 * Shared subprocess/PID/toolset helpers live in [McpIntegrationTestSupport].
 */
class McpToolsetIntegrationTest {

  private val contract =
    McpToolsetContract(
      object : McpToolsetHarness {
        override suspend fun withToolset(
          useMcpResources: Boolean,
          block: suspend (McpToolset) -> Unit,
        ) {
          newToolset(useMcpResources = useMcpResources).use { block(it) }
        }
      }
    )

  /** Skips the whole suite when [DISABLE_IT_ENV] is set to a truthy value. */
  @BeforeTest fun skipIfDisabled() = assumeMcpItEnabled()

  // --- Shared transport contract (see McpToolsetContract) ---

  @Test
  fun getTools_listsToolsAdvertisedByTheServer(): Unit = runBlocking {
    contract.getTools_listsToolsAdvertisedByTheServer()
  }

  @Test
  fun getTools_withUseMcpResources_appendsResourceTools(): Unit = runBlocking {
    contract.getTools_withUseMcpResources_appendsResourceTools()
  }

  @Test
  fun readResource_returnsServerContentEmbeddingTheInjectedToken(): Unit = runBlocking {
    contract.readResource_returnsServerContentEmbeddingTheInjectedToken()
  }

  @Test
  fun run_echoTool_returnsTheArgumentVerbatim(): Unit = runBlocking {
    contract.run_echoTool_returnsTheArgumentVerbatim()
  }

  @Test
  fun run_addTool_returnsServerComputedSum(): Unit = runBlocking {
    contract.run_addTool_returnsServerComputedSum()
  }

  @Test
  fun run_counterTool_incrementsServerStateAcrossCalls(): Unit = runBlocking {
    contract.run_counterTool_incrementsServerStateAcrossCalls()
  }

  @Test
  fun run_failingTool_returnsToolExecutionErrorVerbatim(): Unit = runBlocking {
    contract.run_failingTool_returnsToolExecutionErrorVerbatim()
  }

  @Test
  fun declaration_addTool_convertsServerSchemaToTypedParameters(): Unit = runBlocking {
    contract.declaration_addTool_convertsServerSchemaToTypedParameters()
  }

  // --- stdio-only: process lifecycle ---

  @Test
  fun run_afterServerProcessKilled_respawnsFreshProcessAndRecovers(): Unit = runBlocking {
    val pidFile = Files.createTempFile("adk-mcp-it-pid", ".txt")
    try {
      newToolset(pidFile = pidFile, requestTimeout = KILL_TEST_REQUEST_TIMEOUT).use { toolset ->
        val counter = toolset.getTools().single { it.name == FakeMcpServer.TOOL_COUNTER }

        // First call boots the child process and advances its in-memory counter to 1.
        assertThat(textOf(counter.run(testToolContext(), emptyMap()))).isEqualTo("1")

        // The server records its PID only once it is serving, so the file is populated by now.
        val firstPid = readPid(pidFile)
        val firstHandle = ProcessHandle.of(firstPid).orElseThrow()

        // Unexpected death: external SIGKILL, none of the graceful stdio shutdown. Recovery means
        // the next call reinitializes the pooled session (SessionManager.reinitializeSession),
        // respawning the child; the spec has no stdio reconnect.
        firstHandle.destroyForcibly()
        withTimeout(TimeUnit.SECONDS.toMillis(KILL_TIMEOUT_SECONDS)) {
          val unused = firstHandle.onExit().await()
        }
        assertThat(firstHandle.isAlive).isFalse()

        // Recovered call lands on a fresh respawned process, so the counter resets: reads 1, not 2.
        assertThat(textOf(counter.run(testToolContext(), emptyMap()))).isEqualTo("1")

        // A different, live PID confirms a genuinely new OS process now backs the session.
        val secondPid = readPid(pidFile)
        assertThat(secondPid).isNotEqualTo(firstPid)
        assertThat(ProcessHandle.of(secondPid).orElseThrow().isAlive).isTrue()
      }
    } finally {
      // Belt-and-suspenders: the toolset's close() (via use{}) already tears down the respawned
      // pooled session, but kill the last PID explicitly in case the assertions above failed early.
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  @Test
  fun run_unresponsiveServer_timesOutThenThrowsAfterRetries(): Unit = runBlocking {
    val pidFile = Files.createTempFile("adk-mcp-it-hang-pid", ".txt")
    try {
      newToolset(pidFile = pidFile, requestTimeout = HANG_TEST_REQUEST_TIMEOUT).use { toolset ->
        val hang = toolset.getTools().single { it.name == FakeMcpServer.TOOL_HANG }

        // Each attempt hits the real per-request timeout; the tool retries (reinitializing the
        // pooled session, respawning a process that also hangs) and ultimately throws.
        val start = System.nanoTime()
        val thrown = runCatching { hang.run(testToolContext(), emptyMap()) }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertThat(thrown).isNotNull()
        // Failed via a real timeout, not an instant error: one timeout already exceeds the budget,
        // so a lower bound is robust (the retry storm only makes it longer).
        assertThat(elapsedMs).isAtLeast(HANG_TEST_REQUEST_TIMEOUT.toMillis())
        assertThat(thrown!!.causedByTimeout()).isTrue()
      }
    } finally {
      // Belt-and-suspenders: the toolset's close() (via use{}) already tears down the respawned
      // pooled session, but kill the last PID explicitly in case the assertions above failed early.
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  // Regression guard for the session-ownership leak. Tools no longer own sessions: they share one
  // pooled session owned by the SessionManager, reinit replaces that pooled entry in place, and
  // McpToolset.close() -> SessionManager.closeAll() tears down every session it created. So after a
  // shared-server death, recovery, and close(), no recorded process is left alive.
  @Test
  fun close_afterToolsReinitialize_leavesNoOrphanProcesses(): Unit = runBlocking {
    val pidDir = Files.createTempDirectory("adk-mcp-it-pids")
    val toolset = newToolset(pidDir = pidDir, requestTimeout = HANG_TEST_REQUEST_TIMEOUT)
    try {
      val tools = toolset.getTools()
      val echo = tools.single { it.name == FakeMcpServer.TOOL_ECHO }
      val add = tools.single { it.name == FakeMcpServer.TOOL_ADD }

      // Both tools share the single pooled session: exactly one process so far.
      val shared = liveRecordedProcesses(pidDir)
      assertThat(shared).hasSize(1)

      // Kill the shared server so the next call must reinitialize the pooled session.
      shared.single().destroyForcibly()
      withTimeout(TimeUnit.SECONDS.toMillis(KILL_TIMEOUT_SECONDS)) {
        val unused = shared.single().onExit().await()
      }

      // Drive recovery on both tools, then only confirm recovery (≥1 live process). We don't pin
      // the count: the shared-pool fix keeps it at one (the second call reuses the reinitialized
      // pooled session), but the binding invariant is the post-close check below.
      val unused1 = echo.run(testToolContext(), mapOf("message" to "x"))
      val unused2 = add.run(testToolContext(), mapOf("a" to 1, "b" to 2))
      assertThat(liveRecordedProcesses(pidDir)).isNotEmpty()

      // The invariant under test: after close(), no recorded process is still alive — the toolset
      // tears down every session it caused, including the one respawned during recovery.
      toolset.close()
      assertThat(awaitRecordedProcessesSettle(pidDir, SETTLE_TIMEOUT_SECONDS)).isEmpty()
    } finally {
      // Belt-and-suspenders: never leave orphans behind, even if the guard regresses.
      toolset.close()
      liveRecordedProcesses(pidDir).forEach { it.destroyForcibly() }
      pidDir.toFile().deleteRecursively()
    }
  }

  private companion object {
    /** How long to wait for a SIGKILL'd child process to actually exit before failing. */
    private const val KILL_TIMEOUT_SECONDS: Long = 10

    /**
     * Request timeout for the process-kill test. Kept short because the first post-kill call blocks
     * for the entire timeout (ADK doesn't fail fast on a broken stdio pipe) before recovery kicks
     * in; still ample for the trivial retried call on the respawned process.
     */
    private val KILL_TEST_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

    /**
     * Request timeout for the unresponsive-server test. Kept fairly short because the call hits
     * this timeout on every one of McpTool's retry attempts before giving up, so the cumulative
     * stall is a multiple of it. It can't be too short, though: this single value also bounds the
     * `initialize` handshake (the SDK applies `requestTimeout` to every request, including init),
     * which must complete inside it during the initial `getTools()`. Cold-starting the child JVM on
     * a slow, contended CI runner can exceed a sub-second budget, so a too-small value fails tool
     * loading with an `McpToolLoadingException` before the hang path is ever reached. 3s
     * comfortably absorbs that cold start while keeping the retry storm modest.
     */
    private val HANG_TEST_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

    /** How long to wait, after close(), for the toolset's child processes to actually exit. */
    private const val SETTLE_TIMEOUT_SECONDS: Long = 5
  }
}
