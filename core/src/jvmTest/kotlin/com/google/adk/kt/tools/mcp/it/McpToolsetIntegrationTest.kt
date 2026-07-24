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
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end integration test for `McpToolset` over the **stdio** transport.
 *
 * Launches the real [FakeMcpServer] as a child JVM process and talks to it over actual stdin/stdout
 * pipes, complementing the mock-based `McpToolsetTest` by covering the seams a mocked session can't
 * reach: the subprocess + stdio transport, JSON-RPC (de)serialization, tool-call round-trips, and
 * persistent server state.
 *
 * Shared subprocess/PID/toolset helpers live in [McpIntegrationTestSupport].
 */
class McpToolsetIntegrationTest {

  /** Skips the whole suite when [DISABLE_IT_ENV] is set to a truthy value. */
  @BeforeTest fun skipIfDisabled() = assumeMcpItEnabled()

  @Test
  fun getTools_listsToolsAdvertisedByTheServer(): Unit = runBlocking {
    newToolset().use { toolset ->
      val toolNames = toolset.getTools().map { it.name }
      assertThat(toolNames)
        .containsExactly(
          FakeMcpServer.TOOL_ECHO,
          FakeMcpServer.TOOL_ADD,
          FakeMcpServer.TOOL_COUNTER,
          FakeMcpServer.TOOL_WHOAMI,
          FakeMcpServer.TOOL_SLOW,
          FakeMcpServer.TOOL_FAIL,
          FakeMcpServer.TOOL_HANG,
        )
    }
  }

  @Test
  fun getTools_withUseMcpResources_appendsResourceTools(): Unit = runBlocking {
    newToolset(useMcpResources = true).use { toolset ->
      val toolNames = toolset.getTools().map { it.name }
      // The three resource tools are appended only because the live server advertises the
      // resources capability during the handshake (gated in McpToolset.loadTools); the five server
      // tools remain, so we assert the full, exact set. (The default-config test above proves they
      // are absent when useMcpResources is false.)
      assertThat(toolNames)
        .containsExactly(
          FakeMcpServer.TOOL_ECHO,
          FakeMcpServer.TOOL_ADD,
          FakeMcpServer.TOOL_COUNTER,
          FakeMcpServer.TOOL_WHOAMI,
          FakeMcpServer.TOOL_SLOW,
          FakeMcpServer.TOOL_FAIL,
          FakeMcpServer.TOOL_HANG,
          "list_mcp_resources",
          "load_mcp_resource",
          "list_mcp_resource_templates",
        )
    }
  }

  @Test
  fun readResource_returnsServerContentEmbeddingTheInjectedToken(): Unit = runBlocking {
    newToolset().use { toolset ->
      val contents = toolset.readResource(FakeMcpServer.RESOURCE_GREETING_URI) as List<*>
      val text = (contents.single() as McpSchema.TextResourceContents).text()
      // Proves the env-injection channel and a real resources/read round-trip.
      assertThat(text).contains(INJECTED_TOKEN)
    }
  }

  @Test
  fun run_echoTool_returnsTheArgumentVerbatim(): Unit = runBlocking {
    val message = "round-trip payload"
    newToolset().use { toolset ->
      val echo = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ECHO }
      val result = echo.run(testToolContext(), mapOf("message" to message))
      assertThat(textOf(result)).isEqualTo(message)
    }
  }

  @Test
  fun run_addTool_returnsServerComputedSum(): Unit = runBlocking {
    newToolset().use { toolset ->
      val add = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ADD }
      val result = add.run(testToolContext(), mapOf("a" to 2, "b" to 3))
      // Numeric marshalling, which the string echo test doesn't cover.
      assertThat(textOf(result)).isEqualTo("5")
    }
  }

  @Test
  fun run_counterTool_incrementsServerStateAcrossCalls(): Unit = runBlocking {
    newToolset().use { toolset ->
      val counter = toolset.getTools().single { it.name == FakeMcpServer.TOOL_COUNTER }
      // Same cached session across calls, so the count persists (1 then 2).
      assertThat(textOf(counter.run(testToolContext(), emptyMap()))).isEqualTo("1")
      assertThat(textOf(counter.run(testToolContext(), emptyMap()))).isEqualTo("2")
    }
  }

  @Test
  fun run_failingTool_returnsToolExecutionErrorVerbatim(): Unit = runBlocking {
    newToolset().use { toolset ->
      val fail = toolset.getTools().single { it.name == FakeMcpServer.TOOL_FAIL }
      val result = fail.run(testToolContext(), emptyMap())
      // In-band tool error: returned verbatim (isError=true), not thrown, so no retry path.
      assertThat(isErrorOf(result)).isTrue()
      assertThat(textOf(result)).isEqualTo(FakeMcpServer.FAIL_MESSAGE)
    }
  }

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
        // respawn + re-initialize (McpTool.reinitializeSession); the spec has no stdio reconnect.
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
      // close() won't kill the process McpTool respawned during recovery; kill the last PID
      // explicitly.
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

        // Each attempt hits the real per-request timeout; McpTool retries (respawning a process
        // that also hangs) and ultimately throws.
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
      // close() won't kill the process McpTool respawned during recovery; kill the last PID
      // explicitly.
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  // Regression guard for the session-ownership leak. It FAILS today: when the shared server dies,
  // each tool reinitializes into its OWN session, but McpToolset.close() closes only its stale
  // cached reference -- so the respawned processes are orphaned. A TODO in
  // McpTool.reinitializeSession() marks where the fix belongs; drop @Ignore once it lands.
  @Ignore
  @Test
  fun close_afterToolsReinitialize_leavesNoOrphanProcesses(): Unit = runBlocking {
    val pidDir = Files.createTempDirectory("adk-mcp-it-pids")
    val toolset = newToolset(pidDir = pidDir, requestTimeout = HANG_TEST_REQUEST_TIMEOUT)
    try {
      val tools = toolset.getTools()
      val echo = tools.single { it.name == FakeMcpServer.TOOL_ECHO }
      val add = tools.single { it.name == FakeMcpServer.TOOL_ADD }

      // Both tools share the toolset's single cached session: exactly one process so far.
      val shared = liveRecordedProcesses(pidDir)
      assertThat(shared).hasSize(1)

      // Kill the shared server so the next call on each tool must reinitialize independently.
      shared.single().destroyForcibly()
      withTimeout(TimeUnit.SECONDS.toMillis(KILL_TIMEOUT_SECONDS)) {
        val unused = shared.single().onExit().await()
      }

      // Force each tool to reinitialize, then only confirm recovery (≥1 live process). We
      // deliberately don't pin the count: today each tool respawns its own session (two), but a
      // single-session fix would keep it at one and must still pass. The binding invariant is the
      // post-close check.
      val unused1 = echo.run(testToolContext(), mapOf("message" to "x"))
      val unused2 = add.run(testToolContext(), mapOf("a" to 1, "b" to 2))
      assertThat(liveRecordedProcesses(pidDir)).isNotEmpty()

      // The invariant under test: after close(), no recorded process is still alive — the toolset
      // must tear down every session it caused. Today it closes only its stale cached reference.
      toolset.close()
      assertThat(awaitRecordedProcessesSettle(pidDir, SETTLE_TIMEOUT_SECONDS)).isEmpty()
    } finally {
      // Belt-and-suspenders: never leave orphans behind, even when this guard is failing.
      toolset.close()
      liveRecordedProcesses(pidDir).forEach { it.destroyForcibly() }
      pidDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun declaration_addTool_convertsServerSchemaToTypedParameters(): Unit = runBlocking {
    newToolset().use { toolset ->
      val add = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ADD }
      // declaration() runs McpSchemaConverter over the JSON schema the server returned on the wire
      // (via tools/list), so this checks our conversion against a real schema, not a hand-built
      // one.
      val params = requireNotNull(add.declaration()?.parameters)
      assertThat(params.type).isEqualTo(Type.OBJECT)
      assertThat(params.required).containsExactly("a", "b")
      assertThat(params.properties?.get("a")?.type).isEqualTo(Type.INTEGER)
      assertThat(params.properties?.get("b")?.type).isEqualTo(Type.INTEGER)
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
