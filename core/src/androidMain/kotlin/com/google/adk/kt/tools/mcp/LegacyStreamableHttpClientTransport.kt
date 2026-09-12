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

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.RequestId
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_METHOD_HEADER = "Mcp-Method"
private const val MCP_NAME_HEADER = "Mcp-Name"
private const val MCP_BASE64_PREFIX = "=?base64?"
private const val MCP_BASE64_SUFFIX = "?="
private const val MAX_INLINE_SSE_EVENT_SIZE = 16 * 1024 * 1024

/** HTTP failure surfaced by the compatibility Streamable HTTP transport. */
internal class LegacyStreamableHttpError(
  val code: Int? = null,
  message: String? = null,
) : Exception(
    when {
      code != null && !message.isNullOrBlank() -> "Streamable HTTP error: HTTP $code: $message"
      code != null -> "Streamable HTTP error: HTTP $code"
      else -> "Streamable HTTP error: $message"
    }
  )

private fun sseErrorWithoutPayload(payload: String?): LegacyStreamableHttpError =
  LegacyStreamableHttpError(message = "SSE error event (${payload?.length ?: 0} bytes)")

/**
 * Streamable HTTP compatibility transport for MCP Kotlin SDK 0.5.0.
 *
 * This intentionally mirrors the newer SDK transport's wire contract while implementing the old
 * [AbstractTransport] interface. It is private to the Android adapter, so replacing it with the
 * official transport after the dependency is upgraded requires no ADK API change.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LegacyStreamableHttpClientTransport(
  private val client: HttpClient,
  private val url: String,
  private val requestProgressForToolCalls: Boolean = false,
  private val onProgress: ((Double, Double?, String?) -> Unit)? = null,
  private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractTransport() {
  private val started = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val pendingProgress = ConcurrentHashMap<RequestId, Unit>()
  private var sseJob: Job? = null

  @Volatile private var sessionId: String? = null
  @Volatile private var protocolVersion: String? = null

  override suspend fun start() {
    check(started.compareAndSet(expectedValue = false, newValue = true)) {
      "LegacyStreamableHttpClientTransport is already started."
    }
  }

  override suspend fun send(message: JSONRPCMessage) {
    check(started.load() && !closed.load()) { "Streamable HTTP transport is not connected." }
    val response =
      try {
        client.post(url) {
          applyCommonHeaders(this)
          applyStandardPostHeaders(this, message)
          headers.append(
            HttpHeaders.Accept,
            "${ContentType.Application.Json}, ${ContentType.Text.EventStream}",
          )
          contentType(ContentType.Application.Json)
          setBody(legacyMcpJson.encodeToString(message.withLegacyProgressToken()))
          requestBuilder()
        }
      } catch (error: Throwable) {
        message.removePendingProgress()
        _onError(error)
        throw error
      }

    response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }
    if (response.status == HttpStatusCode.Accepted) {
      if (message is JSONRPCNotification && message.method == "notifications/initialized") {
        startSseSession()
      }
      return
    }
    if (!response.status.isSuccess()) {
      message.removePendingProgress()
      // Drain without copying the body into the exception; status stays on `code`.
      response.bodyAsText()
      throw LegacyStreamableHttpError(response.status.value).also(_onError)
    }

    when (response.contentType()?.withoutParameters()) {
      ContentType.Application.Json -> dispatchJson(response.bodyAsText())
      ContentType.Text.EventStream -> handleInlineSse(response)
      else -> {
        val body = response.bodyAsText()
        if (response.contentType() == null && body.isBlank()) return
        throw LegacyStreamableHttpError(
            -1,
            "Unexpected content type: ${response.contentType()?.toString() ?: "<none>"}",
          )
          .also(_onError)
      }
    }
  }

  /** MCP Kotlin SDK 0.5 registers the callback but does not put its token on the wire. */
  private fun JSONRPCMessage.withLegacyProgressToken(): JSONRPCMessage {
    if (!requestProgressForToolCalls || this !is JSONRPCRequest || method != "tools/call") return this
    val paramsObject = params as? JsonObject ?: JsonObject(emptyMap())
    val meta = paramsObject["_meta"] as? JsonObject ?: JsonObject(emptyMap())
    pendingProgress[id] = Unit
    return copy(
      params =
        JsonObject(
          paramsObject +
            ("_meta" to
              JsonObject(meta + ("progressToken" to id.toJsonPrimitive())))
        )
    )
  }

  private fun RequestId.toJsonPrimitive(): JsonPrimitive =
    when (this) {
      is RequestId.NumberId -> JsonPrimitive(value)
      is RequestId.StringId -> JsonPrimitive(value)
    }

  private fun JSONRPCMessage.removePendingProgress() {
    if (this is JSONRPCRequest) pendingProgress.remove(id)
  }

  override suspend fun close() {
    if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
    try {
      terminateSession()
    } finally {
      sseJob?.cancelAndJoin()
      pendingProgress.clear()
      scope.cancel()
      _onClose()
    }
  }

  private suspend fun terminateSession() {
    if (sessionId == null) return
    val response =
      try {
        client.delete(url) {
          applyCommonHeaders(this)
          requestBuilder()
        }
      } catch (_: Throwable) {
        // The owning Android connection may already have synchronously closed its HTTP client.
        return
      }
    if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
      _onError(
        LegacyStreamableHttpError(
          response.status.value,
          "Failed to terminate session: ${response.status.description}",
        )
      )
    }
    sessionId = null
  }

  private fun startSseSession() {
    if (closed.load() || sseJob?.isActive == true) return
    sseJob =
      scope.launch(CoroutineName("LegacyStreamableHttpTransport.collect#${hashCode()}")) {
        try {
          val session =
            client.sseSession(urlString = url) {
              method = HttpMethod.Get
              applyCommonHeaders(this)
              accept(ContentType.Text.EventStream)
              requestBuilder()
            }
          collectSse(session)
        } catch (_: CancellationException) {
          // Normal during close or synchronous HTTP-client cancellation.
        } catch (error: SSEClientException) {
          val status = error.response?.status
          if (status != HttpStatusCode.NotFound && status != HttpStatusCode.MethodNotAllowed) {
            _onError(error)
          }
        } catch (error: Throwable) {
          if (!closed.load()) _onError(error)
        }
      }
  }

  private suspend fun collectSse(session: ClientSSESession) {
    session.incoming.collect { event ->
      when (event.event) {
        "error" -> _onError(sseErrorWithoutPayload(event.data))
        null, "message" -> event.data?.let { dispatchJson(it) }
      }
    }
  }

  private suspend fun handleInlineSse(response: HttpResponse) {
    val channel = response.bodyAsChannel()
    val data = StringBuilder()
    var eventName: String? = null

    suspend fun dispatch() {
      if (data.isEmpty()) return
      when (eventName) {
        "error" -> _onError(sseErrorWithoutPayload(data.toString()))
        null, "message" -> dispatchJson(data.toString())
      }
      data.clear()
      eventName = null
    }

    while (!channel.isClosedForRead) {
      @Suppress("DEPRECATION")
      val line =
        try {
          channel.readUTF8Line(MAX_INLINE_SSE_EVENT_SIZE)
        } catch (error: TooLongLineException) {
          throw LegacyStreamableHttpError(
            message = "Inline SSE event exceeded $MAX_INLINE_SSE_EVENT_SIZE characters."
          )
        } ?: break
      if (line.isEmpty()) {
        dispatch()
      } else if (line.startsWith("event:")) {
        eventName = line.substringAfter("event:").trim()
      } else if (line.startsWith("data:")) {
        if (data.isNotEmpty()) data.append('\n')
        data.append(line.substringAfter("data:").trimStart())
        if (data.length > MAX_INLINE_SSE_EVENT_SIZE) {
          throw LegacyStreamableHttpError(
            message = "Inline SSE event exceeded $MAX_INLINE_SSE_EVENT_SIZE characters."
          )
        }
      }
    }
    dispatch()
  }

  private suspend fun dispatchJson(json: String) {
    if (json.isBlank()) return
    try {
      if (dispatchLegacyProgress(json)) return
      val message = legacyMcpJson.decodeFromString<JSONRPCMessage>(normalizeForLegacySdk(json))
      _onMessage(message)
      message.responseId()?.let(pendingProgress::remove)
    } catch (error: Throwable) {
      _onError(error)
      throw error
    }
  }

  private fun dispatchLegacyProgress(source: String): Boolean {
    val message =
      runCatching { legacyMcpJson.parseToJsonElement(source).jsonObject }.getOrNull()
        ?: return false
    if (message["method"]?.jsonPrimitive?.contentOrNull != "notifications/progress") return false
    val params = message["params"] as? JsonObject ?: return true
    val token = params["progressToken"]?.jsonPrimitive?.toRequestId() ?: return true
    if (!pendingProgress.containsKey(token)) {
      _onError(IllegalStateException("Received MCP progress for an unknown token."))
      return true
    }
    val progress = params["progress"]?.jsonPrimitive?.doubleOrNull ?: return true
    runCatching {
        onProgress?.invoke(
          progress,
          params["total"]?.jsonPrimitive?.doubleOrNull,
          params["message"]?.jsonPrimitive?.contentOrNull,
        )
      }
      .onFailure(_onError)
    return true
  }

  private fun JsonPrimitive.toRequestId(): RequestId? =
    if (isString) contentOrNull?.let(RequestId::StringId)
    else longOrNull?.let(RequestId::NumberId)

  private fun JSONRPCMessage.responseId(): RequestId? =
    when (this) {
      is io.modelcontextprotocol.kotlin.sdk.JSONRPCResponse -> id
      else -> null
    }

  /**
   * Preserves newer tool information before 0.5.0 deserializes the response.
   *
   * That SDK ignores `outputSchema` and top-level `$defs`. Folding the former into the description
   * and resolving local references in the latter keeps the declaration useful without changing
   * ADK's public API. This shim can be deleted with this transport after the SDK upgrade.
   */
  private fun normalizeForLegacySdk(source: String): String {
    val root =
      runCatching { legacyMcpJson.parseToJsonElement(source).jsonObject }.getOrNull()
        ?: return source
    var result = root["result"] as? JsonObject ?: return source
    (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.let { negotiatedVersion ->
      // 0.5.0 rejects newer negotiated versions before exposing capabilities. The Streamable HTTP
      // protocol is backward compatible for the operations used here, so retain the real version
      // for transport headers while presenting the SDK version it understands to Client.connect.
      protocolVersion = negotiatedVersion
      result = JsonObject(result + ("protocolVersion" to JsonPrimitive(LATEST_PROTOCOL_VERSION)))
    }
    val tools =
      result["tools"] as? kotlinx.serialization.json.JsonArray
        ?: return JsonObject(root + ("result" to result)).toString()
    val normalizedTools =
      tools.map { element ->
        val tool = element as? JsonObject ?: return@map element
        val inputSchema = tool["inputSchema"] as? JsonObject
        val definitions = inputSchema?.get("\$defs") as? JsonObject ?: JsonObject(emptyMap())
        val normalizedInput = inputSchema?.resolveLocalReferences(definitions)
        val outputSchema = tool["outputSchema"]
        val description = (tool["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        JsonObject(
          buildMap {
            putAll(tool)
            normalizedInput?.let { put("inputSchema", it) }
            if (outputSchema != null) {
              val suffix = "Output schema: ${outputSchema}"
              put(
                "description",
                JsonPrimitive(
                  listOf(description, suffix).filter { it.isNotBlank() }.joinToString("\n\n")
                ),
              )
            }
          }
        )
      }
    return JsonObject(
        root +
          ("result" to JsonObject(result + ("tools" to kotlinx.serialization.json.JsonArray(normalizedTools))))
      )
      .toString()
  }

  private fun JsonObject.resolveLocalReferences(
    definitions: JsonObject,
    visiting: Set<String> = emptySet(),
  ): JsonObject {
    val reference = (get("\$ref") as? JsonPrimitive)?.contentOrNull
    if (reference != null && reference.startsWith("#/\$defs/") && reference !in visiting) {
      val target = definitions[reference.substringAfterLast('/')] as? JsonObject
      if (target != null) {
        return JsonObject(target + filterKeys { it != "\$ref" })
          .resolveLocalReferences(definitions, visiting + reference)
      }
    }
    return JsonObject(
      mapValues { (_, value) ->
        when (value) {
          is JsonObject -> value.resolveLocalReferences(definitions, visiting)
          is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.JsonArray(
              value.map { child ->
                if (child is JsonObject) child.resolveLocalReferences(definitions, visiting) else child
              }
            )
          else -> value
        }
      }
    )
  }

  private fun applyCommonHeaders(builder: HttpRequestBuilder) {
    builder.headers {
      sessionId?.let { append(MCP_SESSION_ID_HEADER, it) }
      protocolVersion?.let { append(MCP_PROTOCOL_VERSION_HEADER, it) }
    }
  }

  private fun applyStandardPostHeaders(
    builder: HttpRequestBuilder,
    message: JSONRPCMessage,
  ) {
    val (method, params) =
      when (message) {
        is JSONRPCRequest -> message.method to message.params
        is JSONRPCNotification -> message.method to message.params
        else -> return
      }
    builder.headers {
      append(MCP_METHOD_HEADER, method)
      val paramsObject = params as? JsonObject ?: return@headers
      val name =
        (paramsObject["name"] as? JsonPrimitive)?.contentOrNull
          ?: (paramsObject["uri"] as? JsonPrimitive)?.contentOrNull
      name?.let { append(MCP_NAME_HEADER, it.encodeMcpHeaderValue()) }
    }
  }

  @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
  private fun String.encodeMcpHeaderValue(): String {
    val unsafe = any { it != '\t' && it.code !in 0x20..0x7e }
    val edgeWhitespace =
      firstOrNull()?.isWhitespace() == true || lastOrNull()?.isWhitespace() == true
    val reservedEncoding = startsWith(MCP_BASE64_PREFIX) && endsWith(MCP_BASE64_SUFFIX)
    if (!unsafe && !edgeWhitespace && !reservedEncoding) return this
    return "$MCP_BASE64_PREFIX${kotlin.io.encoding.Base64.Default.encode(encodeToByteArray())}$MCP_BASE64_SUFFIX"
  }
}

@OptIn(ExperimentalSerializationApi::class)
private val legacyMcpJson =
  Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
    explicitNulls = false
  }
