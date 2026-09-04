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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.mcp.McpToolException.McpToolLoadingException
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpSchema
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doReturnConsecutively
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class McpToolsetTest {

  private val noResourcesCapabilities =
    McpSchema.ServerCapabilities(null, null, null, null, null, null)

  private val withResourcesCapabilities =
    McpSchema.ServerCapabilities.builder().resources(false, false).build()

  @Test
  fun getTools_retrievesAndFiltersTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()

    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").build(),
        McpSchema.Tool.builder().name("tool3").description("desc 3").build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    // Create Toolset with a filter that only allows "tool1" and "tool3"
    val filter = ToolFilter.Predicate { tool, _ -> tool.name == "tool1" || tool.name == "tool3" }

    val mcpToolset = McpToolset(mockSessionManager, filter)

    val tools = mcpToolset.getTools()
    assertEquals(2, tools.size)
    assertEquals("tool1", tools[0].name)
    assertEquals("tool3", tools[1].name)

    // The toolset fetches the pooled session from the manager and lists tools on it.
    verifyBlocking(mockSessionManager, times(1)) { getSession(any(), anyOrNull()) }
    verify(mockMcpSession, times(1)).listTools()
  }

  @Test
  fun getTools_defersMalformedSchemaUntilTheToolDeclarationIsRequested() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val malformedTool =
      McpSchema.Tool.builder()
        .name("malformedTool")
        .description("Bad schema")
        .inputSchema(McpSchema.JsonSchema("invalid-type", null, null, false, null, null))
        .build()
    whenever(mockMcpSession.listTools()) doReturn mono {
      McpSchema.ListToolsResult(listOf(malformedTool), null)
    }
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val tool = McpToolset(mockSessionManager).getTools().single()

    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
  }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndServerSupport_includesResourceTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.serverCapabilities) doReturn withResourcesCapabilities

    val toolsResponse = McpSchema.ListToolsResult(emptyList(), null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager, useMcpResources = true)

    val tools = mcpToolset.getTools()

    assertEquals(3, tools.size)
    assertEquals("list_mcp_resources", tools[0].name)
    assertEquals("load_mcp_resource", tools[1].name)
    assertEquals("list_mcp_resource_templates", tools[2].name)
  }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndNoServerSupport_omitsResourceTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.serverCapabilities) doReturn noResourcesCapabilities

    val toolsResponse = McpSchema.ListToolsResult(emptyList(), null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager, useMcpResources = true)

    val tools = mcpToolset.getTools()

    assertEquals(0, tools.size)
  }

  @Test
  fun loadTools_withUseMcpResourcesFalse_omitsResourceTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.serverCapabilities) doReturn withResourcesCapabilities

    val toolsResponse = McpSchema.ListToolsResult(emptyList(), null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager, useMcpResources = false)

    val tools = mcpToolset.getTools()

    assertEquals(0, tools.size)
  }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndNoServerSupport_warnsThatTheToolsWereOmitted() =
    runBlocking {
      val mcpToolset = fakeToolset(noResourcesCapabilities, useMcpResources = true)

      // Otherwise invisible: the model just never calls the tools the author configured.
      val (_, warnings) = capturingWarnings { mcpToolset.getTools() }

      val warning = warnings.single()
      assertContains(warning, "useMcpResources")
      assertContains(warning, "did not report the \"resources\" capability")
      assertContains(warning, "load_mcp_resource")
    }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndUnknownCapabilities_omitsResourceToolsAndWarns() =
    runBlocking {
      // Null capabilities, as the SDK reports before initialization, must read as "no
      // resources" rather than crash.
      val mcpToolset = fakeToolset(useMcpResources = true)

      val (tools, warnings) = capturingWarnings { mcpToolset.getTools() }

      assertEquals(emptyList(), tools)
      assertContains(warnings.single(), "did not report the \"resources\" capability")
    }

  @Test
  fun getTools_withUseMcpResourcesTrueAndAllowList_keepsResourceToolsAndFiltersServerTools() =
    runBlocking {
      // An allow-list over server tools must not reach the resource tools, or it would silently
      // undo useMcpResources.
      val mcpToolset =
        fakeToolset(
          withResourcesCapabilities,
          listOf("read_file", "write_file"),
          ToolFilter.allowList("read_file"),
          useMcpResources = true,
        )

      val (tools, warnings) = capturingWarnings { mcpToolset.getTools() }

      assertEquals(
        listOf(
          "read_file",
          "list_mcp_resources",
          "load_mcp_resource",
          "list_mcp_resource_templates",
        ),
        tools.map { it.name },
      )
      assertEquals(emptyList<String>(), warnings)
    }

  @Test
  fun getTools_withUseMcpResourcesTrueAndPredicateRejectingEverything_keepsResourceTools() =
    runBlocking {
      // A context-aware filter must not make the resource tools come and go per turn.
      val mcpToolset =
        fakeToolset(
          withResourcesCapabilities,
          listOf("read_file"),
          ToolFilter.Predicate { _, _ -> false },
          useMcpResources = true,
        )

      val tools = mcpToolset.getTools()

      assertEquals(
        listOf("list_mcp_resources", "load_mcp_resource", "list_mcp_resource_templates"),
        tools.map { it.name },
      )
    }

  @Test
  fun getTools_withUseMcpResourcesFalseAndAllowList_stillFiltersServerTools() = runBlocking {
    val mcpToolset =
      fakeToolset(
        withResourcesCapabilities,
        listOf("read_file", "write_file"),
        ToolFilter.allowList("read_file"),
      )

    val tools = mcpToolset.getTools()

    assertEquals(listOf("read_file"), tools.map { it.name })
  }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndServerSupport_doesNotWarn() = runBlocking {
    val mcpToolset = fakeToolset(withResourcesCapabilities, useMcpResources = true)

    val (_, warnings) = capturingWarnings { mcpToolset.getTools() }

    assertEquals(emptyList<String>(), warnings)
  }

  @Test
  fun getTools_withDynamicHeadersAndNoServerSupport_warnsOnlyOnce() = runBlocking {
    // A headerProvider disables caching, so tools reload every turn; the warning must not.
    val mcpToolset =
      fakeToolset(noResourcesCapabilities, headerProvider = { emptyMap() }, useMcpResources = true)

    val (loads, warnings) = capturingWarnings { List(3) { mcpToolset.getTools() } }

    assertEquals(3, loads.size)
    assertEquals(1, warnings.size, "expected one warning across three loads, got: $warnings")
  }

  @Test
  fun loadTools_withUseMcpResourcesFalse_doesNotWarn() = runBlocking {
    // Capabilities left unstubbed: the flag must short-circuit before they are read.
    val mcpToolset = fakeToolset()

    val (_, warnings) = capturingWarnings { mcpToolset.getTools() }

    assertEquals(emptyList<String>(), warnings)
  }

  @Test
  fun getTools_retriesOnFailureAndSucceeds() = runTest {
    // The first pooled session fails to list tools; the toolset asks the manager for a fresh
    // session (passing the failed one as `stale`) and the replacement succeeds.
    val failingSession = mock<McpAsyncClient>()
    whenever(failingSession.listTools()).thenThrow(RuntimeException("list failed"))

    val recoveringSession = mock<McpAsyncClient>()
    val toolsList = listOf(McpSchema.Tool.builder().name("tool1").description("desc 1").build())
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(recoveringSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> {
        onBlocking { getSession(any(), anyOrNull()) } doReturnConsecutively
          listOf(failingSession, recoveringSession)
      }

    val mcpToolset = McpToolset(mockSessionManager)
    val tools = mcpToolset.getTools()

    assertEquals(1, tools.size)
    assertEquals("tool1", tools[0].name)
    // Two attempts: the initial fetch plus one recovery fetch.
    verifyBlocking(mockSessionManager, times(2)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_retriesWhenSessionOpenFails_andSucceeds() = runTest {
    // Opening the first session fails (e.g. a network blip during initialize()). Because getSession
    // is inside the retry loop, the failure is retried and the second open succeeds -- rather than
    // crashing the whole toolset init on the first attempt.
    val recoveringSession = mock<McpAsyncClient>()
    val toolsList = listOf(McpSchema.Tool.builder().name("tool1").description("desc 1").build())
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(recoveringSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> {
        onBlocking { getSession(any(), anyOrNull()) }
          .thenThrow(RuntimeException("init failed"))
          .thenReturn(recoveringSession)
      }

    val mcpToolset = McpToolset(mockSessionManager)
    val tools = mcpToolset.getTools()

    assertEquals(1, tools.size)
    assertEquals("tool1", tools[0].name)
    // Two attempts: the failed open plus one recovery open.
    verifyBlocking(mockSessionManager, times(2)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_throwsMcpToolLoadingException_whenRetriesExhausted() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.listTools()).thenThrow(RuntimeException("list failed always"))

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<McpToolLoadingException> { mcpToolset.getTools() }
    // Three attempts: the initial fetch plus two recovery fetches.
    verifyBlocking(mockSessionManager, times(3)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_throwsMcpToolLoadingException_onIllegalArgumentException() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.listTools()).thenThrow(IllegalArgumentException("illegal argument"))

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<McpToolLoadingException> { mcpToolset.getTools() }
    // IllegalArgumentException is not retried.
    verifyBlocking(mockSessionManager, times(1)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_rethrowsCancellationException() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.listTools()).thenThrow(CancellationException("cancelled"))

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<CancellationException> { mcpToolset.getTools() }
    verifyBlocking(mockSessionManager, times(1)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun close_closesAllSessionsViaManager() = runTest {
    val mockSessionManager = mock<SessionManager>()

    val mcpToolset = McpToolset(mockSessionManager)
    mcpToolset.close()

    // The toolset delegates teardown to the manager, which owns every session it created.
    verify(mockSessionManager, times(1)).close()
  }

  @Test
  fun mcpToolsetConfig_toToolset_appliesFilterCorrectly() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val config =
      McpToolset.McpToolsetConfig(
        // sseConnectionParams are required for the public toToolset() to pass validation,
        // but are not used when a sessionManager is provided.
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        toolFilter = ToolFilter.allowList("tool1"),
      )

    val toolset = config.toToolset(mockSessionManager)

    val tools = toolset.getTools()
    assertEquals(1, tools.size)
    assertEquals("tool1", tools[0].name)
  }

  @Test
  fun mcpToolsetConfig_toToolset_predicateFilterIsContextAware() = runBlocking {
    val mockMcpSession = mock<McpAsyncClient>()
    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val context = mock<ReadonlyContext>()
    var received: ReadonlyContext? = null
    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        toolFilter =
          ToolFilter.Predicate { tool, ctx ->
            received = ctx
            tool.name == "tool1"
          },
      )

    val toolset = config.toToolset(mockSessionManager)
    val tools = toolset.getTools(context)

    assertEquals(1, tools.size)
    assertEquals("tool1", tools[0].name)
    assertSame(context, received)
  }

  @Test
  fun mcpToolsetConfig_toToolset_throwsExceptionIfMultipleParamsSet() {
    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        stdioConnectionParams =
          McpConnectionParameters.Stdio(
            serverParameters =
              io.modelcontextprotocol.client.transport.ServerParameters.builder("cmd").build()
          ),
      )

    assertFailsWith<IllegalArgumentException> { config.toToolset() }
  }

  @Test
  fun mcpToolsetConfig_toToolset_throwsExceptionIfNoParamsSet() {
    val config = McpToolset.McpToolsetConfig()
    assertFailsWith<IllegalArgumentException> { config.toToolset() }
  }

  @Test
  fun fromConfig_createsToolsetFromConfig() {
    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234")
      )

    val toolset = config.toToolset()
    assertNotNull(toolset)
  }

  @Test
  fun jvmSession_mapsJavaSdkResourceContentsToSharedModel() = runTest {
    val client = mock<McpAsyncClient>()
    val response =
      McpSchema.ReadResourceResult(
        listOf(
          McpSchema.TextResourceContents("uri1", "text/plain", "text", mapOf("page" to 1)),
          McpSchema.BlobResourceContents("uri2", "application/octet-stream", "YmxvYg=="),
        )
      )
    whenever(client.readResource(McpSchema.ReadResourceRequest("uri1"))) doReturn mono { response }

    val contents = JvmMcpClientSession(client).readResource("uri1")

    assertEquals(
      listOf(
        McpClientResourceContent.Text("uri1", "text/plain", "text", mapOf("page" to 1)),
        McpClientResourceContent.Blob("uri2", "application/octet-stream", "YmxvYg=="),
      ),
      contents,
    )
  }

  @Test
  fun jvmSession_mapsJavaSdkResourcesAndTemplatesToSharedModel() = runTest {
    val client = mock<McpAsyncClient>()
    val resource =
      McpSchema.Resource.builder()
        .name("resource1")
        .uri("corp://resource1")
        .title("Resource One")
        .description("the first resource")
        .mimeType("text/plain")
        .size(1234L)
        .annotations(McpSchema.Annotations(listOf(McpSchema.Role.ASSISTANT), 0.7, "2026-01-01"))
        .meta(mapOf("tenant" to "acme"))
        .build()
    val template =
      McpSchema.ResourceTemplate.builder()
        .name("document")
        .uriTemplate("corp://documents/{id}")
        .title("Document")
        .description("a document by id")
        .mimeType("text/markdown")
        .annotations(McpSchema.Annotations(listOf(McpSchema.Role.USER), 0.5, "2026-01-02"))
        .meta(mapOf("source" to "catalog"))
        .build()
    whenever(client.listResources(isNull())) doReturn
      mono { McpSchema.ListResourcesResult(listOf(resource), "next-resources") }
    whenever(client.listResourceTemplates(isNull())) doReturn
      mono { McpSchema.ListResourceTemplatesResult(listOf(template), "next-templates") }

    val session = JvmMcpClientSession(client)

    assertEquals(
      McpClientResourcePage(
        resources =
          listOf(
            McpClientResource(
              name = "resource1",
              uri = "corp://resource1",
              title = "Resource One",
              description = "the first resource",
              mimeType = "text/plain",
              size = 1234L,
              annotations = McpClientAnnotations(listOf("assistant"), 0.7, "2026-01-01"),
              meta = mapOf("tenant" to "acme"),
            )
          ),
        nextCursor = "next-resources",
      ),
      session.listResources(null),
    )
    assertEquals(
      McpClientResourceTemplatePage(
        resourceTemplates =
          listOf(
            McpClientResourceTemplate(
              name = "document",
              uriTemplate = "corp://documents/{id}",
              title = "Document",
              description = "a document by id",
              mimeType = "text/markdown",
              annotations = McpClientAnnotations(listOf("user"), 0.5, "2026-01-02"),
              meta = mapOf("source" to "catalog"),
            )
          ),
        nextCursor = "next-templates",
      ),
      session.listResourceTemplates(null),
    )
  }

  @Test
  fun mcpToolsetConfig_toToolset_withEmptyFilter_returnsNoTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        toolFilter = ToolFilter.AllowList(emptySet()),
      )

    val toolset = config.toToolset(mockSessionManager)

    val tools = toolset.getTools()
    assertEquals(0, tools.size)
  }
}

/** Returns a minimal server-advertised tool named [name], for tests that only care about names. */
private fun schemaTool(name: String): McpSchema.Tool =
  McpSchema.Tool.builder().name(name).description("desc $name").build()

/**
 * Returns a toolset over a session advertising [serverToolNames] and [capabilities].
 *
 * A null [capabilities] is left unstubbed, so the session answers null as the SDK does before
 * initialization.
 */
private fun fakeToolset(
  capabilities: McpSchema.ServerCapabilities? = null,
  serverToolNames: List<String> = emptyList(),
  toolFilter: ToolFilter? = null,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  useMcpResources: Boolean = false,
): McpToolset {
  val session = mock<McpAsyncClient>()
  capabilities?.let { whenever(session.serverCapabilities) doReturn it }

  val toolsResponse = McpSchema.ListToolsResult(serverToolNames.map { schemaTool(it) }, null)
  whenever(session.listTools()) doReturn mono { toolsResponse }

  val sessionManager =
    mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn session }

  return McpToolset(sessionManager, toolFilter, headerProvider, useMcpResources)
}

/**
 * Runs [block] and returns its result with every warning [McpToolset] logged meanwhile.
 *
 * The facade delegates to Flogger, which is backed by `java.util.logging` and names the logger
 * after the class. Top-level classes only: Flogger joins nested names with `.`, [Class.getName]
 * with `$`.
 */
private suspend fun <T> capturingWarnings(block: suspend () -> T): Pair<T, List<String>> {
  // publish() runs on whichever thread logged, so this cannot be a plain list.
  val records = Collections.synchronizedList(mutableListOf<LogRecord>())
  val handler =
    object : Handler() {
      override fun publish(record: LogRecord?) {
        record?.let { records.add(it) }
      }

      override fun flush() {}

      override fun close() {}
    }

  val logger = Logger.getLogger(McpToolset::class.java.name)
  logger.addHandler(handler)
  val result =
    try {
      block()
    } finally {
      logger.removeHandler(handler)
    }

  // A synchronized list still needs the lock to iterate. Flogger pre-formats, so formatMessage
  // returns the message unchanged.
  return result to
    synchronized(records) {
      records.filter { it.level == Level.WARNING }.map { SimpleFormatter().formatMessage(it) }
    }
}
