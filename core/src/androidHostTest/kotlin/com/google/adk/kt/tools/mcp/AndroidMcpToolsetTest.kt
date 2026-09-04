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
import com.google.adk.kt.tools.Toolset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class AndroidMcpToolsetTest {
  @Test
  fun commonStreamableHttpConfig_usesInternalAndroidAdapter() {
    val toolset =
      McpToolsetConfig(McpTransportConfig.StreamableHttp("https://example.test/mcp")).toToolset()

    assertIs<AndroidMcpToolset>(toolset)
    toolset.close()
  }

  @Test
  fun rejectsNonPositiveTimeouts() {
    assertFailsWith<IllegalArgumentException> { AndroidMcpTimeouts(connect = Duration.ZERO) }
    assertFailsWith<IllegalArgumentException> { AndroidMcpTimeouts(request = Duration.ZERO) }
    assertFailsWith<IllegalArgumentException> { AndroidMcpTimeouts(socket = Duration.ZERO) }
  }

  @Test
  fun commonConfigRejectsCleartextUnlessExplicitlyAllowed() {
    assertFailsWith<IllegalArgumentException> {
      McpToolsetConfig(McpTransportConfig.StreamableHttp("http://example.test/mcp"))
    }
    McpToolsetConfig(
        McpTransportConfig.StreamableHttp(
          "http://example.test/mcp",
          allowInsecureHttp = true,
        ),
      )
      .toToolset()
      .close()
  }

  @Test
  fun getTools_retriesConnectionCreationFailures() = runTest {
    var connectionAttempts = 0
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = {
        connectionAttempts++
        throw IllegalStateException("Network unavailable")
      })

    assertFailsWith<McpToolException.McpToolLoadingException> { toolset.getTools() }

    assertEquals(3, connectionAttempts)
  }

  @Test
  fun getTools_doesNotRetryCancellation() = runTest {
    var connectionAttempts = 0
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = {
        connectionAttempts++
        throw CancellationException("Cancelled")
      })

    assertFailsWith<CancellationException> { toolset.getTools() }

    assertEquals(1, connectionAttempts)
  }

  @Test
  fun getTools_rejectsCallsAfterClose() = runTest {
    val toolset = AndroidMcpToolset("https://example.test/mcp")
    toolset.close()

    assertFailsWith<IllegalStateException> { toolset.getTools() }
  }

  @Test
  fun close_closesTheHttpClientBeforeReturning() = runBlocking {
    val server = FakeStreamableHttpServer()
    lateinit var httpClient: HttpClient
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = {
        server.newClient().also { httpClient = it }
      })

    toolset.getTools()
    assertTrue(httpClient.coroutineContext[Job]?.isActive == true)

    toolset.close()

    assertFalse(httpClient.coroutineContext[Job]?.isActive == true)
  }

  @Test
  fun getTools_cachesToolsListedByStreamableHttpServer() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)

    assertEquals(listOf("echo"), toolset.getTools().map { it.name })
    assertEquals(listOf("echo"), toolset.getTools().map { it.name })

    assertEquals(1, server.listToolsRequests)
  }

  @Test
  fun headerProvider_updatesHeadersWithoutReconnectingTheCurrentSession() = runBlocking {
    val server = FakeStreamableHttpServer()
    var bearerToken = "first-token"
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        headers = mapOf("X-Static" to "static-value", "Authorization" to "static-token"),
        headerProvider = { mapOf("Authorization" to "Bearer $bearerToken") },
        httpClientFactory = server::newClient,
      )
    val context = testToolContext()

    val tool = toolset.getTools(context.context).single()
    bearerToken = "refreshed-token"
    tool.run(context, emptyMap())
    bearerToken = "first-token"
    tool.run(context, emptyMap())

    assertEquals(listOf("Bearer first-token"), server.headersFor("initialize", HttpHeaders.Authorization))
    assertEquals(listOf("static-value"), server.headersFor("initialize", "X-Static"))
    assertEquals(
      listOf("Bearer refreshed-token", "Bearer first-token"),
      server.headersFor("tools/call", HttpHeaders.Authorization),
    )
  }

  @Test
  fun getTools_withoutContextRetainsTheCurrentDynamicHeaderSnapshot() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        headerProvider = { mapOf("Authorization" to "Bearer current-token") },
        httpClientFactory = server::newClient,
      )

    toolset.getTools(testToolContext().context)
    toolset.getTools(null)

    assertEquals(
      listOf("Bearer current-token", "Bearer current-token"),
      server.headersFor("tools/list", HttpHeaders.Authorization),
    )
  }

  @Test
  fun headerProvider_retriesUnauthorizedCallWithRefreshedCredentialsAndNewSession() = runBlocking {
    val server = FakeStreamableHttpServer(unauthorizedToolCallResponses = 1)
    var headerProviderCalls = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        headerProvider = {
          headerProviderCalls++
          mapOf(
            "Authorization" to
              when (headerProviderCalls) {
                1 -> "Bearer initial-token"
                2 -> "Bearer expired-token"
                else -> "Bearer refreshed-token"
              }
          )
        },
        httpClientFactory = server::newClient,
      )
    val context = testToolContext()
    val tool = toolset.getTools(context.context).single()

    tool.run(context, emptyMap())

    assertEquals(
      listOf("Bearer initial-token", "Bearer refreshed-token"),
      server.headersFor("initialize", HttpHeaders.Authorization),
    )
    assertEquals(
      listOf("Bearer expired-token", "Bearer refreshed-token"),
      server.headersFor("tools/call", HttpHeaders.Authorization),
    )
  }

  @Test
  fun sessionNotFound_reinitializesWithoutRefreshingCredentials() = runBlocking {
    val server = FakeStreamableHttpServer(notFoundToolCallResponses = 1)
    var headerProviderCalls = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        headerProvider = {
          headerProviderCalls++
          mapOf("Authorization" to "Bearer current-token")
        },
        httpClientFactory = server::newClient,
      )
    val context = testToolContext()
    val tool = toolset.getTools(context.context).single()

    tool.run(context, emptyMap())

    assertEquals(2, headerProviderCalls)
    assertEquals(
      listOf("Bearer current-token", "Bearer current-token"),
      server.headersFor("initialize", HttpHeaders.Authorization),
    )
  }

  @Test
  fun getTools_reconnectsAfterToolsListFailure() = runBlocking {
    val server = FakeStreamableHttpServer(failedToolListResponses = 1)
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)

    assertEquals(listOf("echo"), toolset.getTools().map { it.name })

    assertEquals(2, server.listToolsRequests)
  }

  @Test
  fun declaration_wrapsMalformedSchemaWithDeclarationException() = runBlocking {
    val server = FakeStreamableHttpServer(malformedToolSchema = true)
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)

    val error = assertFailsWith<McpToolException.McpToolDeclarationException> {
      toolset.getTools().single().declaration()
    }

    assertIs<IllegalArgumentException>(error.cause)
    Unit
  }

  @Test
  fun tool_preservesServerAnnotationsAndMeta() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)

    val tool = toolset.getTools().single() as AndroidMcpTool

    assertEquals("Echo tool", tool.annotations?.title)
    assertEquals(true, tool.annotations?.readOnlyHint)
    assertEquals("test", tool.meta?.get("source")?.jsonPrimitive?.content)
  }

  @Test
  fun callTool_reconnectsAfterToolCallFailure() = runBlocking {
    val server = FakeStreamableHttpServer(failedToolCallResponses = 1)
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)
    toolset.getTools()

    val result = toolset.runMcpTool("echo")

    assertTrue(result.toString().contains("recovered"))
    assertEquals(2, server.toolCallRequests)
  }

  @Test
  fun callTool_allowsConcurrentCallsOnTheSharedConnection() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)
    toolset.getTools()

    val results =
      coroutineScope {
        List(3) {
          async(Dispatchers.Default) {
            toolset.runMcpTool("echo").toString()
          }
        }.awaitAll()
      }

    assertTrue(results.all { it.contains("recovered") })
    assertEquals(3, server.toolCallRequests)
  }

  @Test
  fun getTools_initializesOnlyOnceForConcurrentCallers() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting("https://example.test/mcp", httpClientFactory = server::newClient)

    coroutineScope {
      List(3) { async(Dispatchers.Default) { toolset.getTools().map { it.name } } }.awaitAll()
    }

    assertEquals(1, server.listToolsRequests)
    assertEquals(1, server.initializeRequests)
  }

  @Test
  fun getTools_exposesResourcesOnlyWhenEnabledAndSupported() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val enabled = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = true, httpClientFactory = server::newClient,
    )
    val disabled = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = false, httpClientFactory = server::newClient,
    )

    assertEquals(
      listOf("echo", "list_mcp_resources", "load_mcp_resource", "list_mcp_resource_templates"),
      enabled.getTools().map { it.name },
    )
    assertEquals(listOf("echo"), disabled.getTools().map { it.name })
  }

  @Test
  fun getTools_doesNotFilterAdkResourceTools() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp",
      toolFilter = com.google.adk.kt.tools.ToolFilter.allowList("echo"),
      useMcpResources = true,
      httpClientFactory = server::newClient,
    )

    assertEquals(
      listOf("echo", "list_mcp_resources", "load_mcp_resource", "list_mcp_resource_templates"),
      toolset.getTools().map { it.name },
    )
  }

  @Test
  fun resources_listAndReadUseMcpResourceMethods() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = true, httpClientFactory = server::newClient,
    )
    val resources = toolset.runMcpTool("list_mcp_resources").toString()
    assertTrue(resources.contains("policy"))
    assertTrue(resources.contains("corp://policy"))
    assertEquals("Company policy", toolset.runMcpTool("load_mcp_resource", mapOf("uri" to "corp://policy")))
    assertEquals(1, server.listResourceRequests)
    assertEquals(1, server.readResourceRequests)
  }

  @Test
  fun loadResource_withUriReadsDirectlyWithoutScanningTheCatalog() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = true, httpClientFactory = server::newClient,
    )
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    assertEquals("Company policy", tool.run(testToolContext(), mapOf("uri" to "corp://policy")))
    assertEquals(0, server.listResourceRequests)
    assertEquals(1, server.readResourceRequests)
  }

  @Test
  fun loadResource_malformedArgumentsReturnModelCorrectableMessageWithoutNetworkCall() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = true, httpClientFactory = server::newClient,
    )
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    val result = tool.run(testToolContext(), mapOf("name" to "policy", "uri" to 42)).toString()

    assertTrue(result.contains("both were given"))
    assertTrue(result.contains("\"uri\" is not a string"))
    assertEquals(0, server.listResourceRequests)
    assertEquals(0, server.readResourceRequests)
  }

  @Test
  fun loadResource_truncatesTextAtConfiguredLimit() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp",
      useMcpResources = true,
      maxMcpResourceLength = 5,
      httpClientFactory = server::newClient,
    )
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    assertEquals(
      "Compa... [Content truncated due to size limit]",
      tool.run(testToolContext(), mapOf("uri" to "corp://policy")),
    )
  }

  @Test
  fun loadResource_resourceNotFoundReturnsMessageWithoutRetrying() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true, resourceReadNotFound = true)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = true, httpClientFactory = server::newClient,
    )
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    val result = tool.run(testToolContext(), mapOf("uri" to "corp://missing")).toString()

    assertTrue(result.contains("corp://missing"))
    assertTrue(result.contains("list_mcp_resources"))
    assertEquals(1, server.readResourceRequests)
  }

  @Test
  fun readResource_retriesTransientFailureAndRecovers() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true, failedResourceReadResponses = 1)
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp", useMcpResources = true, httpClientFactory = server::newClient,
    )

    assertEquals("Company policy", toolset.runMcpTool("load_mcp_resource", mapOf("uri" to "corp://policy")))
    assertEquals(2, server.readResourceRequests)
  }

  @Test
  fun callTool_requestsProgressOnlyWhenAConsumerIsConfigured() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp",
      progressConsumers = listOf<(McpProgressUpdate) -> Unit>({ _ -> }),
      httpClientFactory = server::newClient,
    )

    toolset.runMcpTool("echo")

    val params = assertNotNull(server.lastToolCallRequest).getValue("params").jsonObject
    assertTrue(params.getValue("_meta").jsonObject.containsKey("progressToken"))
  }

  @Test
  fun callTool_deliversProgressNotificationsToConfiguredConsumers() = runBlocking {
    val server = FakeStreamableHttpServer(sendsProgress = true)
    val updates = mutableListOf<McpProgressUpdate>()
    val toolset = AndroidMcpToolset.forTesting(
      "https://example.test/mcp",
      progressConsumers = listOf<(McpProgressUpdate) -> Unit>({ progress -> updates.add(progress); Unit }),
      httpClientFactory = server::newClient,
    )

    toolset.runMcpTool("echo")

    assertEquals(1, updates.size)
    assertEquals(0.5, updates.single().progress)
    assertEquals(1.0, updates.single().total)
    assertEquals("Halfway done", updates.single().message)
  }
}

private suspend fun Toolset.runMcpTool(name: String, args: Map<String, Any?> = emptyMap()): Any =
  getTools().single { it.name == name }.run(testToolContext(), args)

private class FakeStreamableHttpServer(
  private var failedToolListResponses: Int = 0,
  private var failedToolCallResponses: Int = 0,
  private var unauthorizedToolCallResponses: Int = 0,
  private var notFoundToolCallResponses: Int = 0,
  private var failedResourceReadResponses: Int = 0,
  private val supportsResources: Boolean = false,
  private val resourceReadNotFound: Boolean = false,
  private val sendsProgress: Boolean = false,
  private val malformedToolSchema: Boolean = false,
) {
  private val initializeRequestCount = AtomicInteger()
  private val listToolsRequestCount = AtomicInteger()
  private val toolCallRequestCount = AtomicInteger()
  private val listResourceRequestCount = AtomicInteger()
  private val readResourceRequestCount = AtomicInteger()
  private val headersByMethod = mutableMapOf<String, MutableList<Map<String, String>>>()
  val initializeRequests: Int get() = initializeRequestCount.get()
  val listToolsRequests: Int get() = listToolsRequestCount.get()
  val toolCallRequests: Int get() = toolCallRequestCount.get()
  val listResourceRequests: Int get() = listResourceRequestCount.get()
  val readResourceRequests: Int get() = readResourceRequestCount.get()
  var lastToolCallRequest: JsonObject? = null
    private set

  fun headersFor(method: String, headerName: String): List<String?> =
    synchronized(headersByMethod) { headersByMethod[method].orEmpty().map { it[headerName] } }

  fun newClient(): HttpClient =
    HttpClient(MockEngine) {
      install(SSE)
      engine {
        addHandler { request -> respondTo(request) }
      }
    }

  private fun MockRequestHandleScope.respondTo(request: HttpRequestData): HttpResponseData {
    val method = request.bodyJson()["method"]?.jsonPrimitive?.content
    synchronized(headersByMethod) {
      headersByMethod.getOrPut(method.orEmpty()) { mutableListOf() } += request.headers.entries()
        .associate { (name, values) -> name to values.joinToString(",") }
    }
    return when (method) {
      "initialize" -> {
        initializeRequestCount.incrementAndGet()
        respondJson(request, initializeResult())
      }
      "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
      "tools/list" -> {
        listToolsRequestCount.incrementAndGet()
        if (failedToolListResponses > 0) {
          failedToolListResponses--
          respond("", HttpStatusCode.ServiceUnavailable)
        } else {
          respondJson(request, toolsResult())
        }
      }
      "tools/call" -> {
        toolCallRequestCount.incrementAndGet()
        lastToolCallRequest = request.bodyJson()
        if (unauthorizedToolCallResponses > 0) {
          unauthorizedToolCallResponses--
          respond("", HttpStatusCode.Unauthorized)
        } else if (notFoundToolCallResponses > 0) {
          notFoundToolCallResponses--
          respond("", HttpStatusCode.NotFound)
        } else if (failedToolCallResponses > 0) {
          failedToolCallResponses--
          respond("", HttpStatusCode.ServiceUnavailable)
        } else if (sendsProgress) {
          respondJsonWithProgress(request, toolCallResult())
        } else {
          respondJson(request, toolCallResult())
        }
      }
      "resources/list" -> {
        listResourceRequestCount.incrementAndGet()
        respondJson(request, resourcesResult())
      }
      "resources/templates/list" -> respondJson(request, resourceTemplatesResult())
      "resources/read" -> {
        readResourceRequestCount.incrementAndGet()
        if (failedResourceReadResponses > 0) {
          failedResourceReadResponses--
          respond("", HttpStatusCode.ServiceUnavailable)
        } else if (resourceReadNotFound) respondError(request, -32002, "Resource not found")
        else respondJson(request, readResourceResult())
      }
      else -> error("Unexpected MCP request: ${request.bodyJson()}")
    }
  }

  private fun MockRequestHandleScope.respondJson(
    request: HttpRequestData,
    result: JsonObject,
  ): HttpResponseData =
    respond(
      content =
        ByteReadChannel(
          "event: message\n" +
            "data: " +
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request.bodyJson().getValue("id"))
                put("result", result)
              } +
            "\n\n"
        ),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )

  private fun MockRequestHandleScope.respondError(
    request: HttpRequestData,
    code: Int,
    message: String,
  ): HttpResponseData =
    respond(
      content =
        ByteReadChannel(
          "event: message\n" +
            "data: " +
            buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", request.bodyJson().getValue("id"))
              put("error", buildJsonObject { put("code", code); put("message", message) })
            } +
            "\n\n"
        ),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )

  private fun MockRequestHandleScope.respondJsonWithProgress(
    request: HttpRequestData,
    result: JsonObject,
  ): HttpResponseData {
    val id = request.bodyJson().getValue("id")
    val progress =
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/progress")
        put(
          "params",
          buildJsonObject {
            put("progressToken", id)
            put("progress", 0.5)
            put("total", 1.0)
            put("message", "Halfway done")
          },
        )
      }
    val success =
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
      }
    return respond(
      content = ByteReadChannel("event: message\ndata: $progress\n\nevent: message\ndata: $success\n\n"),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )
  }

  private fun initializeResult(): JsonObject =
    buildJsonObject {
      put("protocolVersion", "2025-03-26")
      put("capabilities", buildJsonObject {
        put("tools", buildJsonObject {})
        if (supportsResources) put("resources", buildJsonObject {})
      })
      put(
        "serverInfo",
        buildJsonObject {
          put("name", "fake-mcp")
          put("version", "1.0")
        },
      )
    }

  private fun toolsResult(): JsonObject =
    buildJsonObject {
      put(
        "tools",
        Json.parseToJsonElement(
          if (malformedToolSchema) {
            """[{"name":"echo","description":"Echoes a message","inputSchema":{"type":"object","properties":{"bad":{"type":"not-a-json-schema-type"}}}}]"""
          } else {
            """[{"name":"echo","description":"Echoes a message","inputSchema":{"type":"object"},"annotations":{"title":"Echo tool","readOnlyHint":true},"_meta":{"source":"test"}}]"""
          }
        ),
      )
    }

  private fun toolCallResult(): JsonObject =
    buildJsonObject {
      put("content", Json.parseToJsonElement("""[{"type":"text","text":"recovered"}]"""))
    }

  private fun resourcesResult(): JsonObject = buildJsonObject {
    put(
      "resources",
      Json.parseToJsonElement(
        """[{"uri":"corp://policy","name":"policy","title":"Company policy","size":14,"annotations":{"audience":["assistant"],"priority":1.0},"_meta":{"source":"test"}}]"""
      ),
    )
  }

  private fun resourceTemplatesResult(): JsonObject = buildJsonObject {
    put("resourceTemplates", Json.parseToJsonElement("""[]"""))
  }

  private fun readResourceResult(): JsonObject = buildJsonObject {
    put(
      "contents",
      Json.parseToJsonElement(
        """[{"uri":"corp://policy","mimeType":"text/plain","text":"Company policy","_meta":{"source":"test"}}]"""
      ),
    )
  }
}

private fun HttpRequestData.bodyJson(): JsonObject {
  val content = body as OutgoingContent.ByteArrayContent
  return Json.parseToJsonElement(content.bytes().decodeToString()).jsonObject
}
