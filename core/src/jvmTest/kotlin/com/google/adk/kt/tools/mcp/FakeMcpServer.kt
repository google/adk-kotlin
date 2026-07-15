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

@file:JvmName("FakeMcpServerMain")

package com.google.adk.kt.tools.mcp

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Constants shared between the fake MCP server ([main]) and the tests that launch it. */
object FakeMcpServer {
  /** Fully-qualified name of the JVM class that holds [main] (pinned via file-level `@JvmName`). */
  const val MAIN_CLASS = "com.google.adk.kt.tools.mcp.FakeMcpServerMain"

  /** Environment variable the test sets to a random token; the server reflects it back. */
  const val TOKEN_ENV = "ADK_MCP_FAKE_TOKEN"

  /**
   * Environment variable holding a file path. When set, the server writes its PID there at startup
   * so a test can kill this child; each respawn overwrites it.
   */
  const val PID_FILE_ENV = "ADK_MCP_FAKE_PID_FILE"

  /**
   * Environment variable holding a directory path. When set, each process drops a marker file named
   * by its PID into it. Unlike [PID_FILE_ENV] (which a respawn overwrites), this accumulates, so a
   * test can see *every* process the client ever spawned -- needed to catch the per-tool session
   * fan-out on reinitialization.
   */
  const val PID_DIR_ENV = "ADK_MCP_FAKE_PID_DIR"

  const val SERVER_NAME = "adk-fake-mcp-server"
  const val SERVER_VERSION = "0.1.0"

  // Tool names
  const val TOOL_ECHO = "echo"
  const val TOOL_ADD = "add"
  const val TOOL_COUNTER = "counter"
  const val TOOL_WHOAMI = "whoami"
  const val TOOL_SLOW = "slow"
  const val TOOL_FAIL = "fail"
  const val TOOL_HANG = "hang"

  /** Message the [TOOL_FAIL] tool returns inside its `isError: true` result. */
  const val FAIL_MESSAGE = "intentional tool execution error from the 'fail' tool"

  // Resource URIs
  const val RESOURCE_GREETING_URI = "mem://greeting"
}

/** Default number of steps for the [TOOL_SLOW] tool. */
const val DEFAULT_SLOW_STEPS = 3

/**
 * Server-side state proving a single, persistent process handles every request (see [counterTool]).
 */
private val callCounter = AtomicInteger(0)

/**
 * Entry point of the fake MCP server subprocess.
 *
 * IMPORTANT: the MCP stdio transport uses this process's **stdout** as the JSON-RPC channel, so
 * nothing here may print to stdout. All diagnostics go to stderr.
 */
fun main() {
  System.err.println(
    "[fake-mcp] starting ${FakeMcpServer.SERVER_NAME} ${FakeMcpServer.SERVER_VERSION}"
  )
  recordPidIfRequested()

  // The transport reads JSON-RPC requests from System.in and writes responses to System.out. We use
  // the same default JSON mapper the ADK client uses (see DefaultMcpTransportBuilder) so the two
  // ends encode identically.
  val transportProvider = StdioServerTransportProvider(McpJsonDefaults.getMapper())

  val server: McpSyncServer =
    McpServer.sync(transportProvider)
      .serverInfo(FakeMcpServer.SERVER_NAME, FakeMcpServer.SERVER_VERSION)
      .capabilities(
        McpSchema.ServerCapabilities.builder()
          .tools(false) // tools capability present; listChanged = false
          .resources(
            false,
            false,
          ) // resources capability present; subscribe = false, listChanged = false
          .logging() // accept the client's logging consumer
          .build()
      )
      .tools(toolSpecifications())
      .resources(resourceSpecifications())
      .build()

  // There is no in-band "shutdown" message over stdio: the ADK client's StdioClientTransport
  // terminates this child process (SIGTERM) when it closes. The transport also serves requests on
  // background threads, so main must stay alive meanwhile. We therefore park main on a latch and
  // release it from a JVM shutdown hook, which also closes the server gracefully (a safety net
  // against leaking an orphan JVM in CI).
  val shutdownLatch = CountDownLatch(1)
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        server.closeGracefully()
        shutdownLatch.countDown()
      }
    )
  shutdownLatch.await()
}

// Tools

private fun toolSpecifications(): List<SyncToolSpecification> =
  listOf(echoTool(), addTool(), counterTool(), whoamiTool(), slowTool(), failTool(), hangTool())

/**
 * Builds a [SyncToolSpecification] from a tool's [name], [description], [inputSchema], and
 * [handler], hiding the repeated double-builder scaffold so each tool shows only what differs.
 */
private fun syncTool(
  name: String,
  description: String,
  inputSchema: McpSchema.JsonSchema = objectSchema(),
  handler: (McpSyncServerExchange, McpSchema.CallToolRequest) -> McpSchema.CallToolResult,
): SyncToolSpecification =
  SyncToolSpecification.builder()
    .tool(
      McpSchema.Tool.builder().name(name).description(description).inputSchema(inputSchema).build()
    )
    .callHandler { exchange, request -> handler(exchange, request) }
    .build()

/** `echo(message: String) -> message`: proves an argument and a result cross the wire. */
private fun echoTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_ECHO,
    description = "Returns the 'message' argument unchanged.",
    inputSchema =
      objectSchema(properties = mapOf("message" to stringProp()), required = listOf("message")),
  ) { _, request ->
    textResult(argString(request.arguments(), "message"))
  }

/**
 * `add(a: Int, b: Int) -> a + b`: proves typed parameters survive schema conversion + transport.
 */
private fun addTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_ADD,
    description = "Returns the sum of integer arguments 'a' and 'b'.",
    inputSchema =
      objectSchema(
        properties = mapOf("a" to integerProp(), "b" to integerProp()),
        required = listOf("a", "b"),
      ),
  ) { _, request ->
    val args = request.arguments()
    val sum = argInt(args, "a") + argInt(args, "b")
    textResult(sum.toString())
  }

/** `counter() -> n`: increments server-side state; calls returning 1 then 2 prove one process. */
private fun counterTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_COUNTER,
    description = "Returns a server-side counter that increments on every call.",
  ) { _, _ ->
    textResult(callCounter.incrementAndGet().toString())
  }

/** `whoami() -> token`: returns the per-run token the test injected through the environment. */
private fun whoamiTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_WHOAMI,
    description = "Returns the per-run token injected through the environment.",
  ) { _, _ ->
    textResult(injectedToken())
  }

/**
 * `slow(steps: Int = 3) -> "done"`: emits one progress notification per step, but ONLY when the
 * client opted in by supplying a progress token on the request (`request.progressToken()`), echoing
 * that exact token — as the MCP spec requires (progress notifications "MUST only reference tokens
 * that were provided in an active request"). With no token, it simply returns.
 */
private fun slowTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_SLOW,
    description =
      "Emits 'steps' progress notifications when a progress token is provided, then returns.",
    inputSchema = objectSchema(properties = mapOf("steps" to integerProp())),
  ) { exchange, request ->
    val progressToken = request.progressToken()
    if (progressToken != null) {
      val requested = argInt(request.arguments(), "steps")
      val steps = if (requested <= 0) DEFAULT_SLOW_STEPS else requested
      for (i in 1..steps) {
        exchange.progressNotification(
          McpSchema.ProgressNotification(
            progressToken,
            i.toDouble(),
            steps.toDouble(),
            "step $i of $steps",
          )
        )
      }
    }
    textResult("done")
  }

/**
 * `fail() -> isError result`: a spec-compliant tool execution error — a successful JSON-RPC result
 * with [McpSchema.CallToolResult.isError] = true (MCP spec, Server/Tools → "Error Handling"), the
 * in-band error channel. Unlike a protocol error the call completes normally, so `McpTool.run`
 * returns it verbatim without retrying.
 */
private fun failTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_FAIL,
    description = "Always returns a tool-execution error (isError = true).",
  ) { _, _ ->
    McpSchema.CallToolResult.builder()
      .addTextContent(FakeMcpServer.FAIL_MESSAGE)
      .isError(true)
      .build()
  }

/**
 * `hang() -> (never returns)`: blocks the request thread forever so the client's call exhausts its
 * request timeout — a live-but-unresponsive server, as opposed to a dead pipe. The parked thread is
 * abandoned when the client kills this process.
 */
private fun hangTool(): SyncToolSpecification =
  syncTool(
    name = FakeMcpServer.TOOL_HANG,
    description = "Never responds; blocks until the process is killed.",
  ) { _, _ ->
    CountDownLatch(1).await() // a latch that is never counted down: parks here indefinitely
    textResult("unreachable")
  }

// Resources

private fun resourceSpecifications(): List<SyncResourceSpecification> = listOf(greetingResource())

/** `mem://greeting`: a short text resource that embeds the injected token. */
private fun greetingResource(): SyncResourceSpecification {
  val resource =
    McpSchema.Resource.builder()
      .uri(FakeMcpServer.RESOURCE_GREETING_URI)
      .name("greeting")
      .description("A greeting that embeds the per-run token.")
      .mimeType("text/plain")
      .build()
  return SyncResourceSpecification(resource) { _, request ->
    textResource(request.uri(), "hello from ${FakeMcpServer.SERVER_NAME}, token=${injectedToken()}")
  }
}

// Small helpers

private fun injectedToken(): String = System.getenv(FakeMcpServer.TOKEN_ENV) ?: "<no-token>"

/**
 * Records this process's PID so a test can find and kill it.
 * - [FakeMcpServer.PID_FILE_ENV]: a single file, overwritten atomically (latest PID wins).
 * - [FakeMcpServer.PID_DIR_ENV]: a directory holding one marker file per PID (cumulative).
 *
 * Either, both, or neither may be set.
 */
private fun recordPidIfRequested() {
  val pid = ProcessHandle.current().pid()
  System.getenv(FakeMcpServer.PID_FILE_ENV)?.let { path ->
    val target = Path.of(path)
    // Temp file + atomic move so a concurrent reader never sees a half-written value.
    val tmp = Files.createTempFile(target.parent ?: Path.of("."), "fake-mcp-pid", ".tmp")
    Files.writeString(tmp, pid.toString())
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
  }
  System.getenv(FakeMcpServer.PID_DIR_ENV)?.let { path ->
    val dir = Path.of(path)
    Files.createDirectories(dir)
    Files.writeString(dir.resolve(pid.toString()), "") // marker file named by PID
  }
  System.err.println("[fake-mcp] pid=$pid recorded")
}

/** Wraps [text] in a successful, single-text-content tool result. */
private fun textResult(text: String): McpSchema.CallToolResult =
  McpSchema.CallToolResult.builder().addTextContent(text).build()

/** Wraps [text] in a single-text-content resource-read result for [uri]. */
private fun textResource(uri: String, text: String): McpSchema.ReadResourceResult =
  McpSchema.ReadResourceResult(listOf(McpSchema.TextResourceContents(uri, "text/plain", text)))

/** Builds a JSON-Schema `{ "type": "object", ... }` describing a tool's arguments. */
private fun objectSchema(
  properties: Map<String, Any> = emptyMap(),
  required: List<String> = emptyList(),
): McpSchema.JsonSchema =
  // JsonSchema is a Java record, so Kotlin can't use named arguments; the /* name = */ comments
  // label the positional args (several are null) for readability.
  McpSchema.JsonSchema(
    /* type = */ "object",
    /* properties = */ properties,
    /* required = */ required,
    /* additionalProperties = */ null,
    /* defs = */ null,
    /* definitions = */ null,
  )

private fun stringProp(): Map<String, Any> = mapOf("type" to "string")

private fun integerProp(): Map<String, Any> = mapOf("type" to "integer")

// These arg accessors assume the test supplies well-formed inputs (missing/malformed → "" or 0).
private fun argString(args: Map<String, Any?>?, key: String): String =
  args?.get(key)?.toString() ?: ""

private fun argInt(args: Map<String, Any?>?, key: String): Int =
  when (val v = args?.get(key)) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull() ?: 0
    else -> 0
  }
