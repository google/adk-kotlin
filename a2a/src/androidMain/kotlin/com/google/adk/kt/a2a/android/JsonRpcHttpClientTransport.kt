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

package com.google.adk.kt.a2a.android

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.function.Consumer
import org.a2aproject.sdk.client.http.A2AHttpClient
import org.a2aproject.sdk.client.transport.spi.ClientTransport
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest
import org.a2aproject.sdk.spec.A2AClientException
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.CancelTaskParams
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams
import org.a2aproject.sdk.spec.EventKind
import org.a2aproject.sdk.spec.GetExtendedAgentCardParams
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult
import org.a2aproject.sdk.spec.ListTasksParams
import org.a2aproject.sdk.spec.MessageSendParams
import org.a2aproject.sdk.spec.StreamingEventKind
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskIdParams
import org.a2aproject.sdk.spec.TaskPushNotificationConfig
import org.a2aproject.sdk.spec.TaskQueryParams

/**
 * A proto-free [ClientTransport] that runs a real non-streaming `message/send` JSON-RPC round-trip
 * over an injected [A2AHttpClient].
 *
 * The SDK's own JSON-RPC transport can't run on Android: it serializes with protobuf's
 * `JsonFormat`, which needs full protobuf, not the proto-lite runtime Android uses. So this does
 * the round-trip by hand with the SDK's proto-free `jsonrpccommon` [JsonUtil]. The remaining
 * [ClientTransport] methods throw [UnsupportedOperationException].
 *
 * Any [ClientCallInterceptor]s configured on the SDK `Client` are applied to `message/send`: their
 * headers (auth, logging, tracing) are attached to the outgoing request. See
 * [applyInterceptorHeaders].
 *
 * Internal to this module; wired up by `androidA2AClient(...)`.
 */
internal class JsonRpcHttpClientTransport(
  private val httpClient: A2AHttpClient,
  private val url: String,
  private val agentCard: AgentCard? = null,
  private val interceptors: List<ClientCallInterceptor> = emptyList(),
) : ClientTransport {

  override fun sendMessage(request: MessageSendParams, context: ClientCallContext?): EventKind {
    val body = serializeSendMessage(request)
    val interceptorHeaders = applyInterceptorHeaders(SEND_MESSAGE_METHOD, request, context)

    try {
      val response =
        httpClient
          .createPost()
          .url(url)
          .addHeader(A2AHttpClient.CONTENT_TYPE, A2AHttpClient.APPLICATION_JSON)
          .addHeader(A2A_VERSION_HEADER, A2A_VERSION)
          .addHeaders(interceptorHeaders)
          .body(body)
          .post()
      if (response.status() < 200 || response.status() >= 300) {
        throw A2AClientException("Unexpected HTTP status: ${response.status()}")
      }
      return parseSendMessageResponse(response.body())
    } catch (e: A2AClientException) {
      throw e
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw A2AClientException("Android A2A HTTP round-trip interrupted", e)
    } catch (e: Exception) {
      throw A2AClientException("Android A2A HTTP round-trip failed", e)
    }
  }

  /**
   * Serializes a `message/send` request to its JSON-RPC wire form.
   *
   * [JsonUtil] serializes a `Message` (a `StreamingEventKind`) with a `{"<kind>": {...}}` wrapper,
   * which double-wraps `params.message`; a request needs the bare message, so we strip one layer:
   * ```
   * JsonUtil:   "params": { "message": { "message": {...} } }
   * wire form:  "params": { "message": {...} }
   * ```
   */
  private fun serializeSendMessage(request: MessageSendParams): String =
    try {
      val envelope: JsonObject =
        JsonParser.parseString(
            JsonUtil.toJson(SendMessageRequest(JSONRPC_VERSION, REQUEST_ID, request))
          )
          .asJsonObject
      val params = envelope.getAsJsonObject("params")
      params.add("message", params.getAsJsonObject("message").get("message"))
      envelope.toString()
    } catch (e: JsonProcessingException) {
      throw A2AClientException("Failed to serialize A2A request", e)
    }

  /**
   * Runs the configured [interceptors] and returns the HTTP headers to attach to the request.
   *
   * Mirrors the SDK's JSON-RPC transport: it seeds the headers from the call [context], then lets
   * each interceptor add to them in order. Interceptors can also rewrite the payload, but that path
   * is proto-based and doesn't apply to this proto-free transport, so only the headers are used.
   */
  private fun applyInterceptorHeaders(
    methodName: String,
    payload: Any?,
    context: ClientCallContext?,
  ): Map<String, String> {
    var payloadAndHeaders = PayloadAndHeaders(payload, context?.headers)
    for (interceptor in interceptors) {
      payloadAndHeaders =
        interceptor.intercept(
          methodName,
          payloadAndHeaders.payload,
          payloadAndHeaders.headers,
          agentCard,
          context,
        )
    }
    return payloadAndHeaders.headers
  }

  // --- Unused operations -------------------------------------------------------------------------

  override fun sendMessageStreaming(
    request: MessageSendParams,
    eventConsumer: Consumer<StreamingEventKind>,
    errorConsumer: Consumer<Throwable>,
    context: ClientCallContext?,
  ): Unit =
    throw UnsupportedOperationException("streaming not supported by JsonRpcHttpClientTransport")

  override fun getTask(request: TaskQueryParams, context: ClientCallContext?): Task =
    throw UnsupportedOperationException()

  override fun cancelTask(request: CancelTaskParams, context: ClientCallContext?): Task =
    throw UnsupportedOperationException()

  override fun listTasks(request: ListTasksParams, context: ClientCallContext?): ListTasksResult =
    throw UnsupportedOperationException()

  override fun createTaskPushNotificationConfiguration(
    request: TaskPushNotificationConfig,
    context: ClientCallContext?,
  ): TaskPushNotificationConfig = throw UnsupportedOperationException()

  override fun getTaskPushNotificationConfiguration(
    request: GetTaskPushNotificationConfigParams,
    context: ClientCallContext?,
  ): TaskPushNotificationConfig = throw UnsupportedOperationException()

  override fun listTaskPushNotificationConfigurations(
    request: ListTaskPushNotificationConfigsParams,
    context: ClientCallContext?,
  ): ListTaskPushNotificationConfigsResult = throw UnsupportedOperationException()

  override fun deleteTaskPushNotificationConfigurations(
    request: DeleteTaskPushNotificationConfigParams,
    context: ClientCallContext?,
  ): Unit = throw UnsupportedOperationException()

  override fun subscribeToTask(
    request: TaskIdParams,
    eventConsumer: Consumer<StreamingEventKind>,
    errorConsumer: Consumer<Throwable>,
    context: ClientCallContext?,
  ): Unit = throw UnsupportedOperationException()

  override fun getExtendedAgentCard(
    params: GetExtendedAgentCardParams,
    context: ClientCallContext?,
  ): AgentCard = throw UnsupportedOperationException()

  override fun close() {}

  private companion object {
    const val JSONRPC_VERSION = "2.0"
    const val REQUEST_ID = "1"
    const val A2A_VERSION_HEADER = "A2A-Version"
    const val A2A_VERSION = "1.0"
    const val SEND_MESSAGE_METHOD = "message/send"

    /** Parses a JSON-RPC `message/send` response body into its result [EventKind]. */
    fun parseSendMessageResponse(responseBody: String): EventKind {
      val envelope = JsonParser.parseString(responseBody).asJsonObject

      val errorNode = envelope.get("error")
      if (errorNode != null && !errorNode.isJsonNull) {
        throw A2AClientException("A2A JSON-RPC error: $errorNode")
      }

      val resultNode = envelope.get("result")
      if (resultNode == null || !resultNode.isJsonObject) {
        throw A2AClientException("A2A JSON-RPC response missing 'result' object")
      }

      return try {
        // Result is a Task/Message; the SDK's StreamingEventKind adapter picks the concrete type.
        JsonUtil.fromJson(resultNode.toString(), StreamingEventKind::class.java) as EventKind
      } catch (e: JsonProcessingException) {
        throw A2AClientException("Failed to parse A2A response result", e)
      }
    }
  }
}
