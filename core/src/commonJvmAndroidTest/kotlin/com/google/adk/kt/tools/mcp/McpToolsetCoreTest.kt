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
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class McpToolsetCoreTest {
  @Test
  fun sessionManagerDefaultDoesNotApplyAHeaderSnapshot() = runBlocking {
    val manager: McpClientSessionManager = FakeSessionManager(FakeSession())

    manager.getSession()

    assertNull((manager as FakeSessionManager).headersSeen.single())
  }

  @Test
  fun staticHeaders_cacheDiscoveredTools() = runBlocking {
    val session = FakeSession()
    val manager = FakeSessionManager(session)
    val toolset = McpToolsetCore(manager)

    assertThat(toolset.getTools().map { it.name }).containsExactly("echo")
    assertThat(toolset.getTools().map { it.name }).containsExactly("echo")

    assertThat(manager.getSessionCalls).isEqualTo(1)
    assertThat(session.listToolsCalls).isEqualTo(1)
  }

  @Test
  fun discoveryFailure_replacesOnlyFailedSession() = runBlocking {
    val failed = FakeSession(failToolDiscovery = true)
    val replacement = FakeSession()
    val manager = FakeSessionManager(failed, replacement)
    val toolset = McpToolsetCore(manager)

    assertThat(toolset.getTools().map { it.name }).containsExactly("echo")

    assertThat(manager.staleSessions).containsExactly(failed)
    assertThat(replacement.listToolsCalls).isEqualTo(1)
  }

  @Test
  fun resources_areAddedOutsideTheServerToolFilter() = runBlocking {
    val session = FakeSession(supportsResources = true)
    val manager = FakeSessionManager(session)
    val toolset =
      McpToolsetCore(
        manager,
        toolFilter = com.google.adk.kt.tools.ToolFilter.allowList("echo"),
        useMcpResources = true,
      )

    assertThat(toolset.getTools().map { it.name })
      .containsExactly(
        "echo",
        "list_mcp_resources",
        "load_mcp_resource",
        "list_mcp_resource_templates",
      )
    Unit
  }

  @Test
  fun progressConsumers_forwardFunctionCallIdAndListener() = runBlocking {
    val session = FakeSession()
    val manager =
      FakeSessionManager(
        session,
        hasProgressConsumers = true,
        onProgress = {},
      )
    val toolset = McpToolsetCore(manager)
    val tool = toolset.getTools().single()

    tool.run(testToolContext(functionCallId = "call-42"), emptyMap())

    assertThat(session.callOptions.single().progressToken).isEqualTo("call-42")
    assertThat(session.callOptions.single().onProgress).isSameInstanceAs(manager.onProgress)
  }

  @Test
  fun toolExecution_retriesOnlyTheFailedSession() = runBlocking {
    val failed = FakeSession(failToolCalls = 1)
    val replacement = FakeSession()
    val manager = FakeSessionManager(failed, replacement)
    val tool = McpToolsetCore(manager).getTools().single()

    assertThat(tool.run(testToolContext(), emptyMap()))
      .isEqualTo(mapOf("name" to "echo", "arguments" to emptyMap<String, Any?>()))

    assertThat(failed.callToolCalls).isEqualTo(1)
    assertThat(replacement.callToolCalls).isEqualTo(1)
    assertThat(manager.staleSessions).containsExactly(failed)
    Unit
  }

  @Test
  fun dynamicHeaders_areResolvedAgainBeforeToolExecution() = runBlocking {
    val session = FakeSession()
    val manager = FakeSessionManager(session)
    var token = "token-at-discovery"
    val toolset =
      McpToolsetCore(
        manager,
        headerProvider = { mapOf("Authorization" to "Bearer $token") },
      )
    val context = testToolContext()

    val tool = toolset.getTools(context.context).single()
    token = "token-at-execution"
    tool.run(context, emptyMap())

    assertThat(manager.headersSeen)
      .containsExactly(
        mapOf("Authorization" to "Bearer token-at-discovery"),
        mapOf("Authorization" to "Bearer token-at-execution"),
      )
      .inOrder()
    Unit
  }

  @Test
  fun toolFactory_receivesSdkNeutralAnnotationsAndMeta() = runBlocking {
    val definition =
      McpToolDefinition(
        name = "echo",
        description = "Echoes input",
        inputSchema = Schema(type = Type.OBJECT),
        annotations = McpToolAnnotations(title = "Echo", readOnlyHint = true),
        meta = mapOf("source" to "test"),
      )
    var captured: McpToolDefinition? = null
    val toolset =
      McpToolsetCore(
        FakeSessionManager(FakeSession(definition = definition)),
        toolFactory = McpToolFactory { received, invocation ->
          captured = received
          object : com.google.adk.kt.tools.BaseTool(received.name, received.description) {
            override fun declaration() = com.google.adk.kt.types.FunctionDeclaration(name, description)
            override suspend fun run(context: com.google.adk.kt.tools.ToolContext, args: Map<String, Any?>) =
              invocation.invoke(context, args)
          }
        },
      )

    toolset.getTools()

    assertThat(captured?.annotations?.title).isEqualTo("Echo")
    assertThat(captured?.annotations?.readOnlyHint).isTrue()
    assertThat(captured?.meta).containsEntry("source", "test")
  }

  @Test
  fun sharedResourceTool_loadsUriDirectlyAndTruncatesText() = runBlocking {
    val session =
      FakeSession(
        supportsResources = true,
        resourceContents =
          mapOf(
            "corp://policy" to
              listOf(McpClientResourceContent.Text("corp://policy", "text/plain", "Company policy"))
          ),
      )
    val toolset = McpToolsetCore(FakeSessionManager(session), useMcpResources = true, maxMcpResourceLength = 5)
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    assertThat(tool.run(testToolContext(), mapOf("uri" to "corp://policy")))
      .isEqualTo("Compa... [Content truncated due to size limit]")
    assertThat(session.listResourceCalls).isEqualTo(0)
    assertThat(session.readResourceCalls).isEqualTo(1)
  }

  @Test
  fun sharedResourceTool_returnsModelCorrectableErrors() = runBlocking {
    val session = FakeSession(supportsResources = true)
    val toolset = McpToolsetCore(FakeSessionManager(session), useMcpResources = true)
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    assertThat(tool.run(testToolContext(), mapOf("name" to "policy", "uri" to 42)).toString())
      .contains("both were given, and \"uri\" is not a string")
    assertThat(tool.run(testToolContext(), mapOf("uri" to "corp://missing")).toString())
      .contains("call list_mcp_resources")
    assertThat(session.readResourceCalls).isEqualTo(1)
  }

  @Test
  fun sharedResourceTools_preservePaginationForResourceListsAndTemplates() = runBlocking {
    val session =
      FakeSession(
        supportsResources = true,
        resourcePages =
          mapOf(
            null to
              McpClientResourcePage(
                listOf(McpClientResource("policy", "corp://policy", mimeType = "text/plain")),
                nextCursor = "next-resources",
              ),
          ),
        resourceTemplatePages =
          mapOf(
            "templates" to
              McpClientResourceTemplatePage(
                listOf(McpClientResourceTemplate("document", "corp://documents/{id}")),
                nextCursor = "next-templates",
              ),
          ),
      )
    val toolset = McpToolsetCore(FakeSessionManager(session), useMcpResources = true)
    val tools = toolset.getTools()

    val resources = tools.single { it.name == "list_mcp_resources" }.run(testToolContext(), emptyMap()) as Map<*, *>
    val templates =
      tools.single { it.name == "list_mcp_resource_templates" }
        .run(testToolContext(), mapOf("cursor" to "templates")) as Map<*, *>

    assertThat(resources["nextCursor"]).isEqualTo("next-resources")
    assertThat(resources["resources"].toString()).contains("corp://policy")
    assertThat(templates["nextCursor"]).isEqualTo("next-templates")
    assertThat(templates["resourceTemplates"].toString()).contains("corp://documents/{id}")
    assertThat(session.resourceCursors).containsExactly(null)
    assertThat(session.resourceTemplateCursors).containsExactly("templates")
    Unit
  }

  @Test
  fun sharedResourceTool_resolvesNamesAcrossPagesAndReportsAmbiguity() = runBlocking {
    val session =
      FakeSession(
        supportsResources = true,
        resourcePages =
          mapOf(
            null to McpClientResourcePage(listOf(McpClientResource("other", "corp://other")), "page-2"),
            "page-2" to
              McpClientResourcePage(
                listOf(
                  McpClientResource("policy", "corp://policy"),
                  McpClientResource(
                    name = "duplicate",
                    uri = "corp://one",
                    description = "First",
                    mimeType = "text/plain",
                  ),
                  McpClientResource(
                    name = "duplicate",
                    uri = "corp://two",
                    description = "Second",
                    mimeType = "application/json",
                  ),
                )
              ),
          ),
        resourceContents =
          mapOf(
            "corp://policy" to
              listOf(McpClientResourceContent.Text("corp://policy", null, "Policy"))
          ),
      )
    val toolset = McpToolsetCore(FakeSessionManager(session), useMcpResources = true)
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    assertThat(tool.run(testToolContext(), mapOf("name" to "policy"))).isEqualTo("Policy")
    val ambiguous = tool.run(testToolContext(), mapOf("name" to "duplicate")).toString()
    assertThat(ambiguous).contains("ambiguous")
    assertThat(ambiguous).contains("corp://one - First [text/plain]")
    assertThat(ambiguous).contains("corp://two - Second [application/json]")
    assertThat(session.resourceCursors).containsExactly(null, "page-2", null, "page-2")
    Unit
  }

  @Test
  fun sharedResourceTool_wrapsTransportFailuresInThePublicExecutionException() = runBlocking {
    val session = FakeSession(supportsResources = true, failResourceRead = true)
    val toolset = McpToolsetCore(FakeSessionManager(session), useMcpResources = true)
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    val error = assertFailsWith<McpToolException.McpToolExecutionException> {
      tool.run(testToolContext(), mapOf("uri" to "corp://policy"))
    }
    assertThat(error.message).contains("simulated transport failure")
    assertThat(session.readResourceCalls).isEqualTo(3)
  }

  @Test
  fun sharedResourceTool_declarationsPreservePaginationAndArgumentGuidance() = runBlocking {
    val tools =
      McpToolsetCore(FakeSessionManager(FakeSession(supportsResources = true)), useMcpResources = true)
        .getTools()

    val listCursor =
      checkNotNull(
        checkNotNull(tools.single { it.name == "list_mcp_resources" }.declaration())
          .parameters?.properties?.get("cursor")
      )
    val templateCursor =
      checkNotNull(
        checkNotNull(tools.single { it.name == "list_mcp_resource_templates" }.declaration())
          .parameters?.properties?.get("cursor")
      )
    val loadParameters =
      checkNotNull(
        checkNotNull(tools.single { it.name == "load_mcp_resource" }.declaration()).parameters?.properties
      )

    assertThat(listCursor.description).isEqualTo("Optional pagination cursor for listing the next page.")
    assertThat(templateCursor.description).isEqualTo("Optional pagination cursor for listing the next page.")
    assertThat(loadParameters["name"]!!.description).contains("resolving it scans the full listing")
    assertThat(loadParameters["uri"]!!.description).contains("preferred argument")
  }

  private class FakeSession(
    private val failToolDiscovery: Boolean = false,
    override val supportsResources: Boolean = false,
    private val definition: McpToolDefinition =
      McpToolDefinition("echo", "Echoes input", Schema(type = Type.OBJECT)),
    private val resourceContents: Map<String, List<McpClientResourceContent>> = emptyMap(),
    private val resourcePages: Map<String?, McpClientResourcePage> = emptyMap(),
    private val resourceTemplatePages: Map<String?, McpClientResourceTemplatePage> = emptyMap(),
    private val failResourceRead: Boolean = false,
    failToolCalls: Int = 0,
  ) : McpResourceClientSession {
    var listToolsCalls = 0
    var listResourceCalls = 0
    var readResourceCalls = 0
    var callToolCalls = 0
    private var remainingToolCallFailures = failToolCalls
    val resourceCursors = mutableListOf<String?>()
    val resourceTemplateCursors = mutableListOf<String?>()

    override suspend fun listTools(): List<McpToolDefinition> {
      listToolsCalls++
      if (failToolDiscovery) throw IllegalStateException("simulated discovery failure")
      return listOf(definition)
    }

    val callOptions = mutableListOf<McpToolCallOptions>()

    override suspend fun callTool(
      name: String,
      arguments: Map<String, Any?>,
      options: McpToolCallOptions,
    ): Map<String, Any?> {
      callToolCalls++
      callOptions += options
      if (remainingToolCallFailures > 0) {
        remainingToolCallFailures--
        throw IllegalStateException("simulated tool transport failure")
      }
      return mapOf("name" to name, "arguments" to arguments)
    }

    override suspend fun listResources(cursor: String?): McpClientResourcePage {
      listResourceCalls++
      resourceCursors += cursor
      return resourcePages[cursor] ?: McpClientResourcePage(emptyList())
    }

    override suspend fun listResourceTemplates(cursor: String?): McpClientResourceTemplatePage {
      resourceTemplateCursors += cursor
      return resourceTemplatePages[cursor] ?: McpClientResourceTemplatePage(emptyList())
    }

    override suspend fun readResource(uri: String): List<McpClientResourceContent> {
      readResourceCalls++
      if (failResourceRead) throw IllegalStateException("simulated transport failure")
      return resourceContents[uri]
        ?: throw McpResourceNotFoundException(uri, IllegalArgumentException("resource not found"))
    }

    override suspend fun close() = Unit
  }

  private class FakeSessionManager(
    vararg sessions: FakeSession,
    override val hasProgressConsumers: Boolean = false,
    override val onProgress: ((McpProgressUpdate) -> Unit)? = null,
  ) : McpClientSessionManager {
    private val availableSessions = sessions.iterator()
    private var activeSession: FakeSession? = null
    val staleSessions = mutableListOf<McpClientSession>()
    val headersSeen = mutableListOf<Map<String, String>?>()
    var getSessionCalls = 0

    override suspend fun getSession(
      headers: Map<String, String>?,
      stale: McpClientSession?,
    ): McpClientSession {
      getSessionCalls++
      headersSeen += headers
      stale?.let(staleSessions::add)
      if (stale != null || activeSession == null) {
        activeSession = if (availableSessions.hasNext()) availableSessions.next() else activeSession
      }
      return checkNotNull(activeSession)
    }

    override fun close() = Unit
  }
}
