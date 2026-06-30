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

import org.a2aproject.sdk.client.transport.spi.ClientTransportProvider
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.TransportProtocol

/**
 * SPI provider that lets `Client.builder(card).withTransport(...)` build a
 * [JsonRpcHttpClientTransport] via the SDK's public builder. Discovered through `ServiceLoader`.
 */
internal class JsonRpcHttpClientTransportProvider :
  ClientTransportProvider<JsonRpcHttpClientTransport, JsonRpcHttpClientTransportConfig> {

  override fun create(
    config: JsonRpcHttpClientTransportConfig,
    agentCard: AgentCard,
    agentInterface: AgentInterface,
  ): JsonRpcHttpClientTransport =
    JsonRpcHttpClientTransport(
      config.httpClient,
      agentInterface.url(),
      agentCard,
      config.interceptors,
    )

  override fun getTransportProtocol(): String = TransportProtocol.JSONRPC.asString()

  override fun getTransportProtocolClass(): Class<JsonRpcHttpClientTransport> =
    JsonRpcHttpClientTransport::class.java
}
