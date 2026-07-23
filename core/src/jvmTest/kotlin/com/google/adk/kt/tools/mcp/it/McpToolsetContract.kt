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
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.spec.McpSchema

/**
 * Opens an [McpToolset] over the transport under test, runs [block] against it, and tears the
 * toolset (and any backing server) down afterwards.
 *
 * One implementation per transport supplies the lifecycle difference to [McpToolsetContract]: the
 * stdio suite spawns a subprocess (`newToolset(...).use { ... }`), while an HTTP suite stands up an
 * in-process server behind the same interface.
 */
interface McpToolsetHarness {
  suspend fun withToolset(useMcpResources: Boolean, block: suspend (McpToolset) -> Unit)
}

/**
 * The transport-agnostic behavioral contract every `McpToolset` transport must satisfy.
 *
 * The stdio and Streamable HTTP suites verify identical behavior; rather than duplicate it, each
 * supplies a [McpToolsetHarness] and delegates a thin `@Test` to every function here (composition,
 * not inheritance). Transport-specific behavior stays out of this contract and in the respective
 * suite: stdio process lifecycle (kill/respawn, hang-timeout, orphan cleanup) and HTTP header
 * propagation.
 *
 * These run over *both* transports deliberately. Result marshalling and schema conversion are
 * downstream of -- and identical across -- the transport, but re-running them per transport doubles
 * as an end-to-end check that the transport itself round-trips every result shape without
 * corrupting it. Behavior that is purely about the model<->MCP boundary (and thus genuinely
 * transport-agnostic) belongs in a single suite instead; see [McpAgentIntegrationTest].
 */
class McpToolsetContract(private val harness: McpToolsetHarness) {

  suspend fun getTools_listsToolsAdvertisedByTheServer() =
    harness.withToolset(useMcpResources = false) { toolset ->
      assertThat(toolset.getTools().map { it.name }).containsExactly(*ADVERTISED_TOOLS)
    }

  suspend fun getTools_withUseMcpResources_appendsResourceTools() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // The three resource tools are appended only because the live server advertises the resources
      // capability during the handshake (gated in McpToolset.loadTools); the server tools remain,
      // so
      // we assert the full, exact set.
      assertThat(toolset.getTools().map { it.name })
        .containsExactly(*ADVERTISED_TOOLS, *RESOURCE_TOOLS)
    }

  suspend fun readResource_returnsServerContentEmbeddingTheInjectedToken() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val contents = toolset.readResource(FakeMcpServer.RESOURCE_GREETING_URI) as List<*>
      val text = (contents.single() as McpSchema.TextResourceContents).text()
      // Proves the token-injection channel and a real resources/read round-trip.
      assertThat(text).contains(INJECTED_TOKEN)
    }

  suspend fun run_echoTool_returnsTheArgumentVerbatim() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val message = "round-trip payload"
      val echo = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ECHO }
      assertThat(textOf(echo.run(testToolContext(), mapOf("message" to message))))
        .isEqualTo(message)
    }

  suspend fun run_addTool_returnsServerComputedSum() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val add = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ADD }
      // Numeric marshalling, which the string echo test doesn't cover.
      assertThat(textOf(add.run(testToolContext(), mapOf("a" to 2, "b" to 3)))).isEqualTo("5")
    }

  suspend fun run_counterTool_incrementsServerStateAcrossCalls() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val counter = toolset.getTools().single { it.name == FakeMcpServer.TOOL_COUNTER }
      // One cached session backs both calls, so the server-side counter advances by exactly one.
      // Asserting the delta (not absolute 1,2) is the transport-agnostic invariant: it proves state
      // persists across calls on the shared session regardless of the server's starting count. (The
      // stdio suite's process-kill test separately pins that a fresh process resets the count.)
      val first = textOf(counter.run(testToolContext(), emptyMap())).toInt()
      val second = textOf(counter.run(testToolContext(), emptyMap())).toInt()
      assertThat(second).isEqualTo(first + 1)
    }

  suspend fun run_failingTool_returnsToolExecutionErrorVerbatim() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val fail = toolset.getTools().single { it.name == FakeMcpServer.TOOL_FAIL }
      val result = fail.run(testToolContext(), emptyMap())
      // In-band tool error: returned verbatim (isError=true), not thrown, so no retry path.
      assertThat(isErrorOf(result)).isTrue()
      assertThat(textOf(result)).isEqualTo(FakeMcpServer.FAIL_MESSAGE)
    }

  suspend fun declaration_addTool_convertsServerSchemaToTypedParameters() =
    harness.withToolset(useMcpResources = false) { toolset ->
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

  private companion object {
    /** The tools [FakeMcpServer] advertises, in the order `McpToolset` returns them. */
    private val ADVERTISED_TOOLS =
      arrayOf(
        FakeMcpServer.TOOL_ECHO,
        FakeMcpServer.TOOL_ADD,
        FakeMcpServer.TOOL_COUNTER,
        FakeMcpServer.TOOL_WHOAMI,
        FakeMcpServer.TOOL_SLOW,
        FakeMcpServer.TOOL_FAIL,
        FakeMcpServer.TOOL_HANG,
      )

    /** The synthetic tools `McpToolset` appends when `useMcpResources` is enabled. */
    private val RESOURCE_TOOLS =
      arrayOf("list_mcp_resources", "load_mcp_resource", "list_mcp_resource_templates")
  }
}
