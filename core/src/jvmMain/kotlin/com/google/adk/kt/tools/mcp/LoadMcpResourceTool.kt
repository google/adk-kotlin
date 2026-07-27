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
 * A built-in tool that allows the ADK agents to load resources exposed by the MCP server.
 *
 * Requires `useMcpResources = true` in the `McpToolset` configuration.
 */
internal class LoadMcpResourceTool(
  private val mcpToolset: McpToolset,
  private val maxMcpResourceLength: Int,
) : BaseTool("load_mcp_resource", "Load a resource from the MCP server by name.") {
  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    try {
      val name =
        args[NAME] as? String ?: throw IllegalArgumentException("Resource name is required.")
      val readonlyContext = context.invocationContext.toReadonlyContext()

      // Resolve the name against the full listing so collisions are detected reliably.
      val matches = mcpToolset.listAllResources(readonlyContext).filter { it.name == name }
      when (matches.size) {
        0 -> return resourceNotFoundMessage(name)
        1 -> {} // Unique match: fall through and read it below.
        else -> return ambiguousNameMessage(name, matches)
      }

      val contents = mcpToolset.readResource(matches.single().uri, readonlyContext)
      if (contents.isEmpty()) {
        return ""
      }
      return contents.joinToString("\n\n") { content -> render(content) }
    } catch (e: CancellationException) {
      throw e // Re-throw cancellation exceptions as they are not indicative of a tool failure.
    } catch (e: Exception) {
      throw McpToolExecutionException("Failed to load MCP resource: ${e.message}", cause = e)
    }
  }

  private fun render(content: McpResourceContent): String =
    when (content) {
      is McpResourceContent.Text -> {
        val text = content.text
        if (text.length > maxMcpResourceLength) {
          text.take(maxMcpResourceLength) + "... [Content truncated due to size limit]"
        } else {
          text
        }
      }
      is McpResourceContent.Blob ->
        "[Warning: Binary data found at this URI, cannot display raw content]"
    }

  private fun resourceNotFoundMessage(name: String): String =
    "No resource named \"$name\" is available on the MCP server. " +
      "Call list_mcp_resources to see the available resource names."

  private fun ambiguousNameMessage(name: String, matches: List<McpResourceInfo>): String {
    val candidates =
      matches.joinToString("\n") { resource ->
        buildString {
          append("- ")
          append(resource.uri)
          resource.description?.let { append(" — ").append(it) }
          resource.mimeType?.let { append(" [").append(it).append("]") }
        }
      }
    return "The name \"$name\" is ambiguous: ${matches.size} resources share it, so it cannot be " +
      "loaded by name. Candidate URIs:\n$candidates"
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
              NAME to
                Schema(
                  type = Type.STRING,
                  description =
                    "The name of the resource to load, as returned by list_mcp_resources.",
                )
            ),
          required = listOf(NAME),
        ),
    )
  }

  companion object {
    private const val NAME = "name"
  }
}
