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

  suspend fun loadResource_returnsServerContentEmbeddingTheInjectedToken() =
    harness.withToolset(useMcpResources = true) { toolset ->
      val load = toolset.getTools().single { it.name == LOAD_MCP_RESOURCE }
      val text = load.run(testToolContext(), mapOf("uri" to FakeMcpServer.RESOURCE_GREETING_URI))
      // Proves the token-injection channel and a real resources/read round-trip through the
      // common ADK resource tool, rather than JVM-only helper methods.
      assertThat(text.toString()).contains(INJECTED_TOKEN)
    }

  suspend fun listResources_returnsEntryCarryingNameAndUri() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // A real resources/list round-trip: the typed entry carries both the programmatic name (per
      // the spec `title` is the display name, not this) and the canonical uri, and that uri is
      // exactly the identifier readResource takes.
      val list = toolset.getTools().single { it.name == "list_mcp_resources" }
      val response = list.run(testToolContext(), emptyMap()) as Map<*, *>
      val entry =
        (response["resources"] as List<*>)
          .map { it as Map<*, *> }
          .single { it["uri"] == FakeMcpServer.RESOURCE_GREETING_URI }
      assertThat(entry["name"]).isEqualTo(FakeMcpServer.RESOURCE_GREETING_NAME)
    }

  suspend fun run_loadMcpResource_byName_readsTheResourceOverTheWire() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // The point of the name-based tool, against a real server: name and uri genuinely differ
      // here ("greeting" vs "mem://greeting"), so this exercises the resources/list round-trip
      // rather than a uri passed straight through.
      val load = toolset.getTools().single { it.name == LOAD_MCP_RESOURCE }
      val result =
        load.run(testToolContext(), mapOf("name" to FakeMcpServer.RESOURCE_GREETING_NAME))
      assertThat(result.toString()).contains(INJECTED_TOKEN)
    }

  suspend fun run_loadMcpResource_byUri_readsTheResourceOverTheWire() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // The uri path skips resolution entirely, which is what keeps resources reachable that the
      // server never lists (a template expansion, a resource link).
      val load = toolset.getTools().single { it.name == LOAD_MCP_RESOURCE }
      val result = load.run(testToolContext(), mapOf("uri" to FakeMcpServer.RESOURCE_GREETING_URI))
      assertThat(result.toString()).contains(INJECTED_TOKEN)
    }

  suspend fun run_loadMcpResource_unknownName_returnsMessageInsteadOfThrowing() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // A caller mistake must come back as text the model can act on; throwing would abort the
      // agent turn without the model ever learning why.
      val load = toolset.getTools().single { it.name == LOAD_MCP_RESOURCE }
      val result = load.run(testToolContext(), mapOf("name" to "no-such-resource"))
      assertThat(result.toString()).contains("no-such-resource")
      assertThat(result.toString()).contains("list_mcp_resources")
    }

  suspend fun run_loadMcpResource_byUnknownUri_returnsMessageInsteadOfThrowing() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // The real server answers an unknown uri with RESOURCE_NOT_FOUND, and the tool turns that
      // into text the model can act on rather than aborting the turn.
      val load = toolset.getTools().single { it.name == LOAD_MCP_RESOURCE }
      val result = load.run(testToolContext(), mapOf("uri" to "mem://doc"))
      assertThat(result.toString()).contains("mem://doc")
      assertThat(result.toString()).contains("list_mcp_resources")
    }

  suspend fun run_listTemplates_expand_thenLoadByUri_readsTheResource() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // The full template loop against a real server: list the templates, substitute {slug}
      // client-side as the MCP spec intends, then read the expanded uri through
      // load_mcp_resource's `uri` argument. mem://doc/{slug} is deliberately absent from
      // resources/list, so name-based lookup cannot reach it and this is the only path.
      val tools = toolset.getTools()
      val listTemplates = tools.single { it.name == "list_mcp_resource_templates" }
      val load = tools.single { it.name == LOAD_MCP_RESOURCE }

      val listed = listTemplates.run(testToolContext(), emptyMap()) as Map<*, *>
      val templates = listed["resourceTemplates"] as List<*>
      val uriTemplate =
        templates
          .map { it as Map<*, *> }
          .single { it["name"] == FakeMcpServer.RESOURCE_DOC_TEMPLATE_NAME }["uriTemplate"]
      assertThat(uriTemplate).isEqualTo(FakeMcpServer.RESOURCE_DOC_TEMPLATE)

      val expanded = (uriTemplate as String).replace("{slug}", "onboarding")
      val body = load.run(testToolContext(), mapOf("uri" to expanded))

      assertThat(body.toString()).isEqualTo(FakeMcpServer.docContent("onboarding"))
    }

  suspend fun listResources_doesNotEnumerateTemplateMembers() =
    harness.withToolset(useMcpResources = true) { toolset ->
      // Why the template tool is not redundant: its members are not in the listing.
      val list = toolset.getTools().single { it.name == "list_mcp_resources" }
      val response = list.run(testToolContext(), emptyMap()) as Map<*, *>
      val uris = (response["resources"] as List<*>).map { (it as Map<*, *>)["uri"] }
      assertThat(uris).doesNotContain("mem://doc/onboarding")
      assertThat(uris).contains(FakeMcpServer.RESOURCE_GREETING_URI)
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

  suspend fun declaration_annotateTool_convertsSchemaShapesThatUsedToFail() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val annotate = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ANNOTATE }

      val params = requireNotNull(annotate.declaration()?.parameters)

      // A ["null", "string"] union used to throw and take the whole toolset's contract down.
      assertThat(params.properties?.get("note")?.type).isEqualTo(Type.STRING)
      // enum was dropped even though Schema has always carried one.
      assertThat(params.properties?.get("direction")?.enum).containsExactly("EAST", "WEST")
      // The backend rejects an array with no items, so it gets a default.
      assertThat(params.properties?.get("tags")?.items?.type).isEqualTo(Type.STRING)
      // "undeclared" names no property, which the backend also rejects.
      assertThat(params.required).containsExactly("direction")
    }

  suspend fun declaration_annotateTool_carriesConstraintsAndOutputSchema() =
    harness.withToolset(useMcpResources = false) { toolset ->
      val annotate = toolset.getTools().single { it.name == FakeMcpServer.TOOL_ANNOTATE }

      val declaration = requireNotNull(annotate.declaration())
      val params = requireNotNull(declaration.parameters)
      val properties = requireNotNull(params.properties)

      val label = requireNotNull(properties["label"])
      assertThat(label.title).isEqualTo("Label")
      assertThat(label.pattern).isEqualTo("^[a-z ]+$")
      assertThat(label.minLength).isEqualTo(1)
      assertThat(label.maxLength).isEqualTo(80)
      // Still nullable, so the constraints ride alongside the null union rather than replacing it.
      assertThat(label.nullable).isTrue()

      val labels = requireNotNull(properties["labels"])
      assertThat(labels.minItems).isEqualTo(1)
      assertThat(labels.maxItems).isEqualTo(5)

      val priority = requireNotNull(properties["priority"])
      assertThat(priority.format).isEqualTo("int32")
      assertThat(priority.minimum).isEqualTo(1.0)
      assertThat(priority.maximum).isEqualTo(9.0)
      assertThat((priority.default as Number).toInt()).isEqualTo(3)

      // An `anyOf` of X and null folds into a nullable X, keeping the default written beside it.
      val retries = requireNotNull(properties["retries"])
      assertThat(retries.type).isEqualTo(Type.INTEGER)
      assertThat(retries.nullable).isTrue()
      // This default is exactly what the anyOf fold has to carry over.
      assertThat((retries.default as Number).toInt()).isEqualTo(5)

      // The unknown arm is dropped; the one the converter understands survives.
      val extra = requireNotNull(properties["extra"])
      assertThat(extra.anyOf?.map { it.type }).containsExactly(Type.STRING)

      // A `$ref` only resolves if the server's `$defs` block reaches the client, so this is what
      // proves the definitions survive transport rather than only in-process conversion.
      val record = requireNotNull(properties["record"])
      assertThat(record.type).isEqualTo(Type.OBJECT)
      assertThat(record.required).containsExactly("id")
      val id = requireNotNull(record.properties?.get("id"))
      assertThat(id.type).isEqualTo(Type.STRING)
      assertThat(id.maxLength).isEqualTo(8)

      val response = requireNotNull(declaration.response)
      assertThat(response.type).isEqualTo(Type.OBJECT)
      assertThat(response.required).containsExactly("stored")
      assertThat(response.properties?.get("stored")?.type).isEqualTo(Type.BOOLEAN)
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
        FakeMcpServer.TOOL_GET_RECORD,
        FakeMcpServer.TOOL_ANNOTATE,
      )

    private const val LOAD_MCP_RESOURCE = "load_mcp_resource"

    /** The synthetic tools `McpToolset` appends when `useMcpResources` is enabled. */
    private val RESOURCE_TOOLS =
      arrayOf("list_mcp_resources", LOAD_MCP_RESOURCE, "list_mcp_resource_templates")
  }
}
