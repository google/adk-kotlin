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

import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull

/** JVM adapter that keeps the existing Java MCP SDK behind [McpClientSession]. */
internal class JvmMcpClientSession(internal val client: McpAsyncClient) : McpResourceClientSession {
  override val supportsResources: Boolean
    get() = client.serverCapabilities?.resources() != null

  override suspend fun listTools(): List<McpToolDefinition> =
    client.listTools().awaitSingle().tools().map { tool ->
      // Preserve the existing JVM contract: schema conversion is lazy in McpTool.declaration().
      // A malformed schema must not make tools/list fail for every otherwise valid tool.
      McpToolDefinition(
        name = tool.name().orEmpty(),
        description = tool.description().orEmpty(),
        inputSchema = null,
        outputSchema = null,
        annotations = tool.annotations()?.toClientToolAnnotations(),
        meta = tool.meta(),
        platformTool = tool,
      )
    }

  override suspend fun callTool(
    name: String,
    arguments: Map<String, Any?>,
    options: McpToolCallOptions,
  ): Map<String, Any?> =
    client
      .callTool(
        McpSchema.CallToolRequest(
          name,
          arguments,
          options.progressToken?.let { token -> mapOf(PROGRESS_TOKEN_KEY to token) },
        )
      )
      .awaitSingleOrNull()
      ?.let(::toJsonNativeMap)
      ?: mapOf("error" to "MCP framework error: CallToolResult was null")

  override suspend fun listResources(cursor: String?): McpClientResourcePage =
    client.listResources(cursor).awaitSingle().let { result ->
      McpClientResourcePage(
        resources =
          result.resources().map { resource ->
            McpClientResource(
              name = resource.name().orEmpty(),
              uri = resource.uri().orEmpty(),
              title = resource.title(),
              description = resource.description(),
              mimeType = resource.mimeType(),
              size = resource.size(),
              annotations = resource.annotations()?.toClientAnnotations(),
              meta = resource.meta(),
            )
          },
        nextCursor = result.nextCursor(),
      )
    }

  override suspend fun listResourceTemplates(cursor: String?): McpClientResourceTemplatePage =
    client.listResourceTemplates(cursor).awaitSingle().let { result ->
      McpClientResourceTemplatePage(
        resourceTemplates =
          result.resourceTemplates().map { template ->
            McpClientResourceTemplate(
              name = template.name().orEmpty(),
              uriTemplate = template.uriTemplate().orEmpty(),
              title = template.title(),
              description = template.description(),
              mimeType = template.mimeType(),
              annotations = template.annotations()?.toClientAnnotations(),
              meta = template.meta(),
            )
          },
        nextCursor = result.nextCursor(),
      )
    }

  override suspend fun readResource(uri: String): List<McpClientResourceContent> =
    try {
      client.readResource(McpSchema.ReadResourceRequest(uri)).awaitSingle().contents().map { content ->
        when (content) {
          is McpSchema.TextResourceContents ->
            McpClientResourceContent.Text(
              content.uri().orEmpty(),
              content.mimeType(),
              content.text().orEmpty(),
              content.meta(),
            )
          is McpSchema.BlobResourceContents ->
            McpClientResourceContent.Blob(
              content.uri().orEmpty(),
              content.mimeType(),
              content.blob().orEmpty(),
              content.meta(),
            )
          // ResourceContents is an open interface in MCP Java SDK 2.0. Keep the SDK boundary
          // explicit rather than allowing an unknown SDK subtype to enter the shared sealed model.
          else -> error("Unsupported ResourceContents type: ${content::class}")
        }
      }
    } catch (error: McpError) {
      if (error.jsonRpcError?.code() == McpSchema.ErrorCodes.RESOURCE_NOT_FOUND) {
        throw McpResourceNotFoundException(uri, error)
      }
      throw error
    }

  override suspend fun close() {
    client.close()
  }

  internal fun isClient(candidate: McpAsyncClient): Boolean = client === candidate

  private companion object {
    private val jsonMapper = McpJsonDefaults.getMapper()

    @Suppress("UNCHECKED_CAST")
    fun toJsonNativeMap(result: McpSchema.CallToolResult): Map<String, Any?> =
      jsonMapper.convertValue(result, Map::class.java) as Map<String, Any?>
  }
}

/** Adapts the existing JVM session pool without changing its public configuration types. */
internal class JvmMcpClientSessionManager(private val delegate: SessionManager) : McpClientSessionManager {
  override val hasProgressConsumers: Boolean
    get() = delegate.hasProgressConsumers

  override suspend fun getSession(
    headers: Map<String, String>?,
    stale: McpClientSession?,
  ): McpClientSession {
    val staleClient = stale as? JvmMcpClientSession
    val rawSession =
      delegate.getSession(
        headers.orEmpty(),
        // The Java pool expects the exact SDK client identity. A session from a different platform
        // adapter is deliberately ignored.
        stale = staleClient?.client,
      )
    return if (staleClient?.isClient(rawSession) == true) staleClient else JvmMcpClientSession(rawSession)
  }

  override fun close() = delegate.close()
}

private const val PROGRESS_TOKEN_KEY = "progressToken"

private fun McpSchema.Annotations.toClientAnnotations() =
  McpClientAnnotations(
    audience = audience().orEmpty().map { it.name.lowercase() },
    priority = priority(),
    lastModified = lastModified(),
  )

private fun McpSchema.ToolAnnotations.toClientToolAnnotations() =
  McpToolAnnotations(title(), readOnlyHint(), destructiveHint(), idempotentHint(), openWorldHint())
