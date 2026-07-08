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

// This file lives in the SDK's package so it can reach the package-private `Client` constructor,
// letting us build a Client directly from our transport without the ServiceLoader SPI.
package org.a2aproject.sdk.client

import org.a2aproject.sdk.client.config.ClientConfig
import org.a2aproject.sdk.client.transport.spi.ClientTransport
import org.a2aproject.sdk.spec.AgentCard

/** Builds a [Client] directly from [transport], bypassing the ServiceLoader-based transport SPI. */
internal fun clientWithTransport(agentCard: AgentCard, transport: ClientTransport): Client =
  Client(agentCard, ClientConfig.Builder().build(), transport, emptyList(), null)
