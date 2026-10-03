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

@file:OptIn(com.google.adk.kt.annotations.FrameworkInternalApi::class)

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Type
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        McpTransportConfig.StreamableHttp("http://example.test/mcp", allowInsecureHttp = true)
      )
      .toToolset()
      .close()
  }

  @Test
  fun getTools_retriesConnectionCreationFailures() = runTest {
    var connectionAttempts = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = {
          connectionAttempts++
          throw IllegalStateException("Network unavailable")
        },
      )

    assertFailsWith<McpToolException.McpToolLoadingException> { toolset.getTools() }

    assertEquals(3, connectionAttempts)
  }

  @Test
  fun getTools_doesNotRetryCancellation() = runTest {
    var connectionAttempts = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = {
          connectionAttempts++
          throw CancellationException("Cancelled")
        },
      )

    assertFailsWith<CancellationException> { toolset.getTools() }

    assertEquals(1, connectionAttempts)
  }

  @Test
  fun getTools_doesNotRetryOrReconnectWhenCallerCancelsDuringConnect() = runBlocking {
    val server = FakeStreamableHttpServer(hangInitialize = true)
    var connectionAttempts = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = {
          connectionAttempts++
          server.newClient()
        },
      )

    val deferred = async(Dispatchers.Default) { toolset.getTools() }
    server.awaitInitializeStarted()
    deferred.cancel()

    assertFailsWith<CancellationException> { deferred.await() }
    delay(300)
    assertEquals(1, server.initializeRequests)
    assertEquals(1, connectionAttempts)
  }

  @Test
  fun callTool_doesNotRetryOrReconnectWhenCallerCancelsDuringCall() = runBlocking {
    val server = FakeStreamableHttpServer(hangToolCall = true)
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )
    val tool = toolset.getTools().single()
    val initializeRequestsAfterDiscovery = server.initializeRequests

    val deferred = async(Dispatchers.Default) { tool.run(testToolContext(), emptyMap()) }
    server.awaitToolCallStarted()
    deferred.cancel()

    assertFailsWith<CancellationException> { deferred.await() }
    delay(300)
    assertEquals(1, server.toolCallRequests)
    assertEquals(initializeRequestsAfterDiscovery, server.initializeRequests)
  }

  @Test
  fun getTools_propagatesParentTimeoutCancellationWithoutRetry() = runBlocking {
    val server = FakeStreamableHttpServer(hangInitialize = true)
    var connectionAttempts = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        timeouts = AndroidMcpTimeouts(connect = 5.seconds, request = 5.seconds, socket = 5.seconds),
        httpClientFactory = {
          connectionAttempts++
          server.newClient()
        },
      )

    val deferred =
      async(Dispatchers.Default) { withTimeout(200.milliseconds) { toolset.getTools() } }
    server.awaitInitializeStarted()

    val error = assertFailsWith<TimeoutCancellationException> { deferred.await() }

    delay(300)
    assertFalse(error.message.orEmpty().contains("MCP connect timed out"))
    assertEquals(1, server.initializeRequests)
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
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = { server.newClient().also { httpClient = it } },
      )

    toolset.getTools()
    val job = assertNotNull(httpClient.coroutineContext[Job])
    assertTrue(job.isActive)

    toolset.close()

    // HttpClient.close() starts shutting the client scope down before this returns. MockEngine +
    // SSE teardown can leave isActive true for a beat on a loaded CI runner.
    withTimeout(2.seconds) { job.join() }
    assertFalse(job.isActive)
  }

  @Test
  fun getTools_cachesToolsListedByStreamableHttpServer() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    assertEquals(listOf("echo"), toolset.getTools().map { it.name })
    assertEquals(listOf("echo"), toolset.getTools().map { it.name })

    assertEquals(1, server.listToolsRequests)
  }

  @Test
  fun transport_reusesSessionAndNegotiatedProtocolHeaders() = runBlocking {
    val server = FakeStreamableHttpServer(sessionId = "session-1")
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    toolset.getTools()

    assertEquals(listOf("session-1"), server.headersFor("tools/list", "mcp-session-id"))
    assertEquals(listOf("2025-03-26"), server.headersFor("tools/list", "mcp-protocol-version"))
  }

  @Test
  fun transport_acceptsApplicationJsonResponses() = runBlocking {
    val server = FakeStreamableHttpServer(respondWithJson = true)
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    assertEquals(listOf("echo"), toolset.getTools().map { it.name })
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

    assertEquals(
      listOf("Bearer first-token"),
      server.headersFor("initialize", HttpHeaders.Authorization),
    )
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
  fun headerProvider_retriesUnauthorizedInitializeWithRefreshedCredentials() = runBlocking {
    val server = FakeStreamableHttpServer(unauthorizedInitializeResponses = 1)
    var headerProviderCalls = 0
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        headerProvider = {
          headerProviderCalls++
          mapOf(
            "Authorization" to
              if (headerProviderCalls == 1) "Bearer expired-token" else "Bearer refreshed-token"
          )
        },
        httpClientFactory = server::newClient,
      )

    assertEquals(listOf("echo"), toolset.getTools(testToolContext().context).map { it.name })
    assertEquals(2, headerProviderCalls)
    assertEquals(
      listOf("Bearer expired-token", "Bearer refreshed-token"),
      server.headersFor("initialize", HttpHeaders.Authorization),
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
    val server = FakeStreamableHttpServer(sessionId = "session-1", notFoundToolCallResponses = 1)
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
    assertEquals(listOf("session-1"), server.deletedSessionIds)
  }

  @Test
  fun getTools_reconnectsAfterToolsListFailure() = runBlocking {
    val server = FakeStreamableHttpServer(failedToolListResponses = 1)
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    assertEquals(listOf("echo"), toolset.getTools().map { it.name })

    assertEquals(2, server.listToolsRequests)
  }

  @Test
  fun declaration_wrapsMalformedSchemaWithDeclarationException() = runBlocking {
    val server = FakeStreamableHttpServer(malformedToolSchema = true)
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    val error =
      assertFailsWith<McpToolException.McpToolDeclarationException> {
        toolset.getTools().single().declaration()
      }

    assertIs<IllegalArgumentException>(error.cause)
    Unit
  }

  @Test
  fun tool_mapsOutputSchemaOntoTheDeclaration() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    val tool = toolset.getTools().single()
    val declaration = assertNotNull(tool.declaration())
    val place = assertNotNull(declaration.parameters?.properties?.get("place"))
    val echoed = assertNotNull(declaration.response?.properties?.get("echoed"))

    assertEquals(Type.OBJECT, place.type)
    assertEquals(Type.STRING, place.properties?.get("city")?.type)
    assertEquals(Type.STRING, echoed.type)
  }

  @Test
  fun tool_preservesServerAnnotationsAndMeta() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    val tool = toolset.getTools().single() as AndroidMcpTool

    assertEquals(McpToolAnnotations(title = "Echo tool", readOnlyHint = true), tool.annotations)
    assertEquals("test", tool.meta?.get("source"))
  }

  @Test
  fun callTool_reconnectsAfterToolCallFailure() = runBlocking {
    val server = FakeStreamableHttpServer(failedToolCallResponses = 1)
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )
    toolset.getTools()

    val result = toolset.runMcpTool("echo")

    assertTrue(result.toString().contains("recovered"))
    assertEquals(2, server.toolCallRequests)
  }

  @Test
  fun callTool_allowsConcurrentCallsOnTheSharedConnection() = runBlocking {
    val server = FakeStreamableHttpServer(sendsProgress = true)
    val progressUpdates = AtomicInteger()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        progressConsumers =
          listOf({
            progressUpdates.incrementAndGet()
            Unit
          }),
        httpClientFactory = server::newClient,
      )
    toolset.getTools()

    val results = coroutineScope {
      List(3) { async(Dispatchers.Default) { toolset.runMcpTool("echo").toString() } }.awaitAll()
    }

    assertTrue(results.all { it.contains("recovered") })
    assertEquals(3, server.toolCallRequests)
    assertEquals(3, progressUpdates.get())
  }

  @Test
  fun callTool_retriesWhenASiblingCallInvalidatesTheSharedConnection() = runBlocking {
    val server =
      FakeStreamableHttpServer(
        sessionId = "session-1",
        hangingToolCallResponses = 1,
        notFoundToolCallResponses = 1,
      )
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )
    toolset.getTools()

    val results =
      withTimeout(10.seconds) {
        coroutineScope {
          val inFlight = async(Dispatchers.Default) { toolset.runMcpTool("echo").toString() }
          server.awaitToolCallStarted()
          val invalidating = async(Dispatchers.Default) { toolset.runMcpTool("echo").toString() }
          listOf(inFlight.await(), invalidating.await())
        }
      }

    assertTrue(results.all { it.contains("recovered") })
    assertEquals(listOf("session-1"), server.deletedSessionIds)
    assertTrue(server.toolCallRequests >= 3)
    assertEquals(2, server.initializeRequests)
  }

  @Test
  fun getTools_initializesOnlyOnceForConcurrentCallers() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    coroutineScope {
      List(3) { async(Dispatchers.Default) { toolset.getTools().map { it.name } } }.awaitAll()
    }

    assertEquals(1, server.listToolsRequests)
    assertEquals(1, server.initializeRequests)
  }

  @Test
  fun getTools_exposesResourcesOnlyWhenEnabledAndSupported() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val enabled =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        useMcpResources = true,
        httpClientFactory = server::newClient,
      )
    val disabled =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        useMcpResources = false,
        httpClientFactory = server::newClient,
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
    val toolset =
      AndroidMcpToolset.forTesting(
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
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        useMcpResources = true,
        httpClientFactory = server::newClient,
      )
    val resources = toolset.runMcpTool("list_mcp_resources").toString()
    assertTrue(resources.contains("policy"))
    assertTrue(resources.contains("corp://policy"))
    assertEquals(
      "Company policy",
      toolset.runMcpTool("load_mcp_resource", mapOf("uri" to "corp://policy")),
    )
    assertEquals(1, server.listResourceRequests)
    assertEquals(1, server.readResourceRequests)
  }

  @Test
  fun loadResource_withUriReadsDirectlyWithoutScanningTheCatalog() = runBlocking {
    val server = FakeStreamableHttpServer(supportsResources = true)
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        useMcpResources = true,
        httpClientFactory = server::newClient,
      )
    val tool = toolset.getTools().single { it.name == "load_mcp_resource" }

    assertEquals("Company policy", tool.run(testToolContext(), mapOf("uri" to "corp://policy")))
    assertEquals(0, server.listResourceRequests)
    assertEquals(1, server.readResourceRequests)
  }

  @Test
  fun loadResource_malformedArgumentsReturnModelCorrectableMessageWithoutNetworkCall() =
    runBlocking {
      val server = FakeStreamableHttpServer(supportsResources = true)
      val toolset =
        AndroidMcpToolset.forTesting(
          "https://example.test/mcp",
          useMcpResources = true,
          httpClientFactory = server::newClient,
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
    val toolset =
      AndroidMcpToolset.forTesting(
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
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        useMcpResources = true,
        httpClientFactory = server::newClient,
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
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        useMcpResources = true,
        httpClientFactory = server::newClient,
      )

    assertEquals(
      "Company policy",
      toolset.runMcpTool("load_mcp_resource", mapOf("uri" to "corp://policy")),
    )
    assertEquals(2, server.readResourceRequests)
  }

  @Test
  fun callTool_serializesNestedMapsAndListsAsJson() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = server::newClient,
      )

    toolset.runMcpTool(
      "echo",
      mapOf("location" to mapOf("city" to "NYC", "limit" to 3), "tags" to listOf("a", "b")),
    )

    val arguments =
      assertNotNull(server.lastToolCallRequest)
        .getValue("params")
        .jsonObject
        .getValue("arguments")
        .jsonObject
    val location = arguments.getValue("location").jsonObject
    assertEquals("NYC", location.getValue("city").jsonPrimitive.content)
    assertEquals(3, location.getValue("limit").jsonPrimitive.int)
    assertEquals(
      listOf("a", "b"),
      arguments.getValue("tags").jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun callTool_requestsProgressOnlyWhenAConsumerIsConfigured() = runBlocking {
    val server = FakeStreamableHttpServer()
    val toolset =
      AndroidMcpToolset.forTesting(
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
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        progressConsumers =
          listOf<(McpProgressUpdate) -> Unit>({ progress ->
            updates.add(progress)
            Unit
          }),
        httpClientFactory = server::newClient,
      )

    toolset.runMcpTool("echo")

    assertEquals(1, updates.size)
    assertEquals(1.0, updates.single().progress)
    assertEquals(1.0, updates.single().total)
    assertEquals("Halfway done", updates.single().message)
  }

  @Test
  fun httpErrorBodies_areStrippedFromConnectAndCallFailures() = runBlocking {
    val connectServer = FakeStreamableHttpServer(failedInitializeResponses = 3)
    val connectToolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = connectServer::newClient,
      )
    val connectError =
      assertFailsWith<McpToolException.McpToolLoadingException> { connectToolset.getTools() }
    connectError.assertDoesNotLeakSensitiveHttpBody()

    val listServer = FakeStreamableHttpServer(failedToolListResponses = 3)
    val listToolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = listServer::newClient,
      )
    val listError =
      assertFailsWith<McpToolException.McpToolLoadingException> { listToolset.getTools() }
    listError.assertDoesNotLeakSensitiveHttpBody()

    val callServer = FakeStreamableHttpServer(failedToolCallResponses = 4)
    val callToolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = callServer::newClient,
      )
    callToolset.getTools()
    val callError =
      assertFailsWith<McpToolException.McpToolExecutionException> { callToolset.runMcpTool("echo") }
    callError.assertDoesNotLeakSensitiveHttpBody()
  }

  @Test
  fun serviceUnavailableBody_doesNotLookLikeUnauthorized() = runBlocking {
    val server = FakeStreamableHttpServer(failedToolListResponses = 1)
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

    assertEquals(listOf("echo"), toolset.getTools(testToolContext().context).map { it.name })

    assertEquals(1, headerProviderCalls)
    assertEquals(1, server.initializeRequests)
    assertEquals(2, server.listToolsRequests)
    assertTrue(server.deletedSessionIds.isEmpty())
  }

  @Test
  fun jsonRpcErrorMentioningHttp401_doesNotLookLikeUnauthorized() = runBlocking {
    val server = FakeStreamableHttpServer(jsonRpcHttp401ToolListResponses = 1)
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

    assertEquals(listOf("echo"), toolset.getTools(testToolContext().context).map { it.name })

    assertEquals(1, headerProviderCalls)
    assertEquals(1, server.initializeRequests)
    assertEquals(2, server.listToolsRequests)
    assertTrue(server.deletedSessionIds.isEmpty())
  }

  @Test
  fun malformedJsonResponses_areStrippedFromConnectAndCallFailures() = runBlocking {
    val connectServer = FakeStreamableHttpServer(malformedInitializeResponses = 3)
    val connectToolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = connectServer::newClient,
      )
    val connectError =
      assertFailsWith<McpToolException.McpToolLoadingException> { connectToolset.getTools() }
    connectError.assertDoesNotLeakSensitiveHttpBody()
    assertEquals(3, connectServer.initializeRequests)

    val listServer = FakeStreamableHttpServer(malformedToolListResponses = 3)
    val listToolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = listServer::newClient,
      )
    val listError =
      assertFailsWith<McpToolException.McpToolLoadingException> { listToolset.getTools() }
    listError.assertDoesNotLeakSensitiveHttpBody()
    assertEquals(3, listServer.listToolsRequests)

    val callServer = FakeStreamableHttpServer(malformedToolCallResponses = 4)
    val callToolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = callServer::newClient,
      )
    callToolset.getTools()
    val callError =
      assertFailsWith<McpToolException.McpToolExecutionException> { callToolset.runMcpTool("echo") }
    callError.assertDoesNotLeakSensitiveHttpBody()
    assertEquals(4, callServer.toolCallRequests)
  }

  @Test
  fun callTool_cancelsQuicklyWhenDeleteHangsAfterSessionNotFound() = runBlocking {
    val server =
      FakeStreamableHttpServer(
        sessionId = "session-1",
        notFoundToolCallResponses = 1,
        hangDelete = true,
      )
    lateinit var httpClient: HttpClient
    val toolset =
      AndroidMcpToolset.forTesting(
        "https://example.test/mcp",
        httpClientFactory = { server.newClient().also { httpClient = it } },
      )
    val tool = toolset.getTools().single()
    val httpClientJob = assertNotNull(httpClient.coroutineContext[Job])

    val deferred = async(Dispatchers.Default) { tool.run(testToolContext(), emptyMap()) }
    server.awaitDeleteStarted()
    withTimeout(1.seconds) {
      deferred.cancel()
      assertFailsWith<CancellationException> { deferred.await() }
    }
    withTimeout(2.seconds) { httpClientJob.join() }
    assertFalse(httpClientJob.isActive)
  }
}

private suspend fun Toolset.runMcpTool(name: String, args: Map<String, Any?> = emptyMap()): Any =
  getTools().single { it.name == name }.run(testToolContext(), args)

private const val SENSITIVE_HTTP_ERROR_BODY = "server log: HTTP 401 token=LEAKED_MCP_ERROR_BODY"

private fun Throwable.assertDoesNotLeakSensitiveHttpBody() {
  var current: Throwable? = this
  while (current != null) {
    assertFalse(
      current.message.orEmpty().contains("LEAKED_MCP_ERROR_BODY"),
      "Sensitive HTTP body leaked: ${current.message}",
    )
    current = current.cause
  }
}

private class FakeStreamableHttpServer(
  private var failedToolListResponses: Int = 0,
  private var failedToolCallResponses: Int = 0,
  private var failedInitializeResponses: Int = 0,
  private var unauthorizedToolCallResponses: Int = 0,
  private var unauthorizedInitializeResponses: Int = 0,
  private var notFoundToolCallResponses: Int = 0,
  private var failedResourceReadResponses: Int = 0,
  private var malformedInitializeResponses: Int = 0,
  private var malformedToolListResponses: Int = 0,
  private var malformedToolCallResponses: Int = 0,
  private var hangingToolCallResponses: Int = 0,
  private var jsonRpcHttp401ToolListResponses: Int = 0,
  private val supportsResources: Boolean = false,
  private val resourceReadNotFound: Boolean = false,
  private val sendsProgress: Boolean = false,
  private val malformedToolSchema: Boolean = false,
  private val sessionId: String? = null,
  private val respondWithJson: Boolean = false,
  private val hangInitialize: Boolean = false,
  private val hangToolCall: Boolean = false,
  private val hangDelete: Boolean = false,
) {
  private val initializeRequestCount = AtomicInteger()
  private val listToolsRequestCount = AtomicInteger()
  private val toolCallRequestCount = AtomicInteger()
  private val listResourceRequestCount = AtomicInteger()
  private val readResourceRequestCount = AtomicInteger()
  private val headersByMethod = mutableMapOf<String, MutableList<Map<String, String>>>()
  val initializeRequests: Int
    get() = initializeRequestCount.get()

  val listToolsRequests: Int
    get() = listToolsRequestCount.get()

  val toolCallRequests: Int
    get() = toolCallRequestCount.get()

  val listResourceRequests: Int
    get() = listResourceRequestCount.get()

  val readResourceRequests: Int
    get() = readResourceRequestCount.get()

  var lastToolCallRequest: JsonObject? = null
    private set

  private val deletedSessionIdList = mutableListOf<String?>()
  val deletedSessionIds: List<String?>
    get() = synchronized(deletedSessionIdList) { deletedSessionIdList.toList() }

  private val initializeStarted = CompletableDeferred<Unit>()
  private val toolCallStarted = CompletableDeferred<Unit>()
  private val deleteStarted = CompletableDeferred<Unit>()

  suspend fun awaitInitializeStarted() = initializeStarted.await()

  suspend fun awaitToolCallStarted() = toolCallStarted.await()

  suspend fun awaitDeleteStarted() = deleteStarted.await()

  fun headersFor(method: String, headerName: String): List<String?> =
    synchronized(headersByMethod) { headersByMethod[method].orEmpty().map { it[headerName] } }

  fun newClient(): HttpClient =
    HttpClient(MockEngine) {
      install(SSE)
      engine { addHandler { request -> respondTo(request) } }
    }

  private suspend fun MockRequestHandleScope.respondTo(request: HttpRequestData): HttpResponseData {
    if (request.method == HttpMethod.Delete) {
      synchronized(deletedSessionIdList) {
        deletedSessionIdList += request.headers["mcp-session-id"]
      }
      deleteStarted.complete(Unit)
      if (hangDelete) awaitCancellation()
      return respond("", HttpStatusCode.OK)
    }
    if (request.method == HttpMethod.Get) {
      return respond("", HttpStatusCode.MethodNotAllowed)
    }
    val method = request.bodyJson()["method"]?.jsonPrimitive?.content
    synchronized(headersByMethod) {
      headersByMethod.getOrPut(method.orEmpty()) { mutableListOf() } +=
        request.headers.entries().associate { (name, values) -> name to values.joinToString(",") }
    }
    return when (method) {
      "initialize" -> {
        initializeRequestCount.incrementAndGet()
        initializeStarted.complete(Unit)
        if (hangInitialize) awaitCancellation()
        if (failedInitializeResponses > 0) {
          failedInitializeResponses--
          respondHttpError(HttpStatusCode.ServiceUnavailable)
        } else if (unauthorizedInitializeResponses > 0) {
          unauthorizedInitializeResponses--
          respondHttpError(HttpStatusCode.Unauthorized)
        } else if (malformedInitializeResponses > 0) {
          malformedInitializeResponses--
          respondMalformed()
        } else {
          respondJson(request, initializeResult(), sessionId)
        }
      }
      "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
      "tools/list" -> {
        listToolsRequestCount.incrementAndGet()
        if (failedToolListResponses > 0) {
          failedToolListResponses--
          respondHttpError(HttpStatusCode.ServiceUnavailable)
        } else if (jsonRpcHttp401ToolListResponses > 0) {
          jsonRpcHttp401ToolListResponses--
          respondError(request, -32603, "HTTP 401 token=not-a-status")
        } else if (malformedToolListResponses > 0) {
          malformedToolListResponses--
          respondMalformed()
        } else {
          respondJson(request, toolsResult())
        }
      }
      "tools/call" -> {
        toolCallRequestCount.incrementAndGet()
        lastToolCallRequest = request.bodyJson()
        toolCallStarted.complete(Unit)
        if (hangToolCall) awaitCancellation()
        if (hangingToolCallResponses > 0) {
          hangingToolCallResponses--
          // Accepted POST, no JSON-RPC result: Protocol waits until the sibling close()
          // completes the pending handler with CONNECTION_CLOSED.
          respond("", HttpStatusCode.Accepted)
        } else if (unauthorizedToolCallResponses > 0) {
          unauthorizedToolCallResponses--
          respondHttpError(HttpStatusCode.Unauthorized)
        } else if (notFoundToolCallResponses > 0) {
          notFoundToolCallResponses--
          respondHttpError(HttpStatusCode.NotFound)
        } else if (failedToolCallResponses > 0) {
          failedToolCallResponses--
          respondHttpError(HttpStatusCode.ServiceUnavailable)
        } else if (malformedToolCallResponses > 0) {
          malformedToolCallResponses--
          respondMalformed()
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
          respondHttpError(HttpStatusCode.ServiceUnavailable)
        } else if (resourceReadNotFound) respondError(request, -32002, "Resource not found")
        else respondJson(request, readResourceResult())
      }
      else -> error("Unexpected MCP request: ${request.bodyJson()}")
    }
  }

  private fun MockRequestHandleScope.respondJson(
    request: HttpRequestData,
    result: JsonObject,
    responseSessionId: String? = null,
  ): HttpResponseData =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.bodyJson().getValue("id"))
        put("result", result)
      }
      .let { response ->
        respond(
          content =
            ByteReadChannel(
              if (respondWithJson) response.toString() else "event: message\ndata: $response\n\n"
            ),
          status = HttpStatusCode.OK,
          headers =
            Headers.build {
              append(
                HttpHeaders.ContentType,
                if (respondWithJson) ContentType.Application.Json.toString()
                else ContentType.Text.EventStream.toString(),
              )
              responseSessionId?.let { append("mcp-session-id", it) }
            },
        )
      }

  private fun MockRequestHandleScope.respondHttpError(status: HttpStatusCode): HttpResponseData =
    respond(
      content = ByteReadChannel(SENSITIVE_HTTP_ERROR_BODY),
      status = status,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
    )

  private fun MockRequestHandleScope.respondMalformed(): HttpResponseData =
    respond(
      content = ByteReadChannel("""{"token":"$SENSITIVE_HTTP_ERROR_BODY""""),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
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
              put(
                "error",
                buildJsonObject {
                  put("code", code)
                  put("message", message)
                },
              )
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
    val progress = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", "notifications/progress")
      put(
        "params",
        buildJsonObject {
          put("progressToken", id)
          put("progress", 1)
          put("total", 1.0)
          put("message", "Halfway done")
        },
      )
    }
    val success = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
    return respond(
      content =
        ByteReadChannel("event: message\ndata: $progress\n\nevent: message\ndata: $success\n\n"),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )
  }

  private fun initializeResult(): JsonObject = buildJsonObject {
    put("protocolVersion", "2025-03-26")
    put(
      "capabilities",
      buildJsonObject {
        put("tools", buildJsonObject {})
        if (supportsResources) put("resources", buildJsonObject {})
      },
    )
    put(
      "serverInfo",
      buildJsonObject {
        put("name", "fake-mcp")
        put("version", "1.0")
      },
    )
  }

  private fun toolsResult(): JsonObject = buildJsonObject {
    put(
      "tools",
      Json.parseToJsonElement(
        if (malformedToolSchema) {
          """[{"name":"echo","description":"Echoes a message","inputSchema":{"type":"object","properties":{"bad":{"type":"not-a-json-schema-type"}}}}]"""
        } else {
          """[{"name":"echo","description":"Echoes a message","inputSchema":{"type":"object","properties":{"place":{"${'$'}ref":"#/${'$'}defs/Place"}},"${'$'}defs":{"Place":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}},"outputSchema":{"type":"object","properties":{"echoed":{"type":"string"}}},"annotations":{"title":"Echo tool","readOnlyHint":true},"_meta":{"source":"test"}}]"""
        }
      ),
    )
  }

  private fun toolCallResult(): JsonObject = buildJsonObject {
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
