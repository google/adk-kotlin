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

import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.McpToolException.McpToolExecutionException
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.CancellationException

/**
 * A built-in tool that allows the ADK agents to list resources exposed by the MCP server.
 *
 * The tool is a thin model-facing adapter: it delegates to [McpToolset.listResources] and flattens
 * the typed result into the plain map shape the model consumes.
 */
internal class ListMcpResourcesTool(private val mcpToolset: McpToolset) :
  BaseTool("list_mcp_resources", DESCRIPTION) {

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    try {
      val cursor = args["cursor"] as? String
      val listing = mcpToolset.listResources(cursor, context.invocationContext.toReadonlyContext())

      val resources =
        listing.resources.map { resource ->
          buildMap {
            put(RESOURCE_NAME, resource.name)
            put(RESOURCE_URI, resource.uri)
            resource.description?.let { description -> put(RESOURCE_DESCRIPTION, description) }
            resource.mimeType?.let { mimeType -> put(RESOURCE_MIME_TYPE, mimeType) }
          }
        }

      val response = mutableMapOf<String, Any>("resources" to resources)
      listing.nextCursor?.let { response["nextCursor"] = it }
      return response
    } catch (e: CancellationException) {
      throw e // Re-throw cancellation exceptions as they are not indicative of a tool failure.
    } catch (e: Exception) {
      throw McpToolExecutionException("Failed to list MCP resources: ${e.message}", cause = e)
    }
  }

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              "cursor" to
                Schema(
                  type = Type.STRING,
                  description = "Optional pagination cursor for listing the next page.",
                )
            ),
          required = emptyList(),
        ),
    )
  }

  companion object {
    private const val DESCRIPTION =
      "List resources available on the MCP server. Returns one page of resources and, when more " +
        "pages are available, a 'nextCursor' value. To fetch the next page, call this tool again " +
        "passing that value as the 'cursor' argument; repeat until no 'nextCursor' is returned."

    const val RESOURCE_NAME = "name"
    const val RESOURCE_URI = "uri"
    const val RESOURCE_DESCRIPTION = "description"
    const val RESOURCE_MIME_TYPE = "mimeType"
  }
}
