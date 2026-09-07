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
import com.google.adk.kt.tools.Toolset
import io.modelcontextprotocol.spec.McpSchema
import java.time.Duration

/** JVM adapter: the public common config continues to use ADK's existing Java MCP SDK client. */
internal actual fun createPlatformMcpToolset(
  config: McpToolsetConfig,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)?,
  progressConsumers: List<(McpProgressUpdate) -> Unit>,
): Toolset =
  when (val transport = config.transport) {
    is McpTransportConfig.StreamableHttp ->
      McpToolset.McpToolsetConfig(
          streamableHttpConnectionParams =
            McpConnectionParameters.StreamableHttp(
              url = transport.url,
              headers = transport.headers,
              timeout = Duration.ofMillis(transport.connectTimeout.inWholeMilliseconds),
              readTimeout = Duration.ofMillis(transport.requestTimeout.inWholeMilliseconds),
            ),
          toolFilter = config.toolFilter,
          useMcpResources = config.useMcpResources,
          maxMcpResourceLength = config.maxMcpResourceLength,
        )
        .toToolset(
          headerProvider = headerProvider,
          progressConsumers = progressConsumers.map { consumer ->
            { notification: McpSchema.ProgressNotification ->
              consumer(
                McpProgressUpdate(
                  progress = notification.progress(),
                  total = notification.total(),
                  message = notification.message(),
                )
              )
            }
          },
        )
  }
