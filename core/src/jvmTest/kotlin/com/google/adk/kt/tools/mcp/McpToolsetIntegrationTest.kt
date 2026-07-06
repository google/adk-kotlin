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

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.mcp.McpToolset.McpToolsetConfig
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Path
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * End-to-end integration test for [McpToolset] over the **stdio** transport.
 *
 * Launches the real [FakeMcpServer] as a child JVM process and talks to it over actual stdin/stdout
 * pipes, complementing the mock-based `McpToolsetTest` by covering the seams a mocked session can't
 * reach: the subprocess + stdio transport, JSON-RPC (de)serialization, tool-call round-trips, and
 * persistent server state.
 */
class McpToolsetIntegrationTest {

  /**
   * Skips the whole suite when [DISABLE_IT_ENV] is set to a truthy value. The tests otherwise run
   * by default, since the fake server has no external dependencies.
   */
  @BeforeTest
  fun skipIfDisabled() {
    val disabled =
      setOf("true", "t", "yes", "y", "1").contains(System.getenv(DISABLE_IT_ENV)?.lowercase())
    Assume.assumeFalse("MCP integration tests disabled via $DISABLE_IT_ENV", disabled)
  }

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

  /** Builds an [McpToolset] wired to a freshly-spawned [FakeMcpServer] subprocess. */
  // TODO(b/529753915): `progressConsumers` is plumbed but intentionally unexercised. ADK's
  // McpTool.run never sets a progressToken on outgoing tool calls, so a spec-conformant server
  // emits no progress and the consumer never fires. Add a conformant progress test once that gap is
  // closed.
  private fun newToolset(
    token: String = INJECTED_TOKEN,
    useMcpResources: Boolean = false,
    progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
  ): McpToolset =
    McpToolsetConfig(
        stdioConnectionParams =
          McpConnectionParameters.Stdio(
            serverParameters = fakeServerParameters(token),
            timeoutDuration = REQUEST_TIMEOUT,
          ),
        useMcpResources = useMcpResources,
      )
      .toToolset(progressConsumers = progressConsumers)

  /** Extracts the text of a single-[McpSchema.TextContent] result returned by [McpTool.run]. */
  private fun textOf(toolResult: Any): String =
    ((toolResult as McpSchema.CallToolResult).content().single() as McpSchema.TextContent).text()

  private companion object {
    /** Env var that, when truthy, skips this suite (e.g. sandboxes that forbid subprocesses). */
    private const val DISABLE_IT_ENV = "ADK_MCP_DISABLE_IT"

    /** Request timeout for stdio calls; generous to absorb cold child-JVM startup on CI. */
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    /**
     * A fixed, non-semantic token injected into the server's environment and reflected back by the
     * `whoami` tool and `mem://greeting` resource.
     */
    private const val INJECTED_TOKEN = "injected-token-probe"

    /**
     * Builds the [ServerParameters] that launch [FakeMcpServer] as a child JVM.
     *
     * We reuse this test JVM's own `java` binary and classpath, which already contain the fake
     * server class and the MCP SDK (the SDK is a `jvmMain` dependency, visible on the test runtime
     * classpath).
     */
    private fun fakeServerParameters(token: String): ServerParameters =
      ServerParameters.builder(javaBinary())
        .args("-cp", System.getProperty("java.class.path"), FakeMcpServer.MAIN_CLASS)
        .addEnvVar(FakeMcpServer.TOKEN_ENV, token)
        .build()

    private fun javaBinary(): String =
      Path.of(System.getProperty("java.home")!!, "bin", "java").toString()
  }
}
