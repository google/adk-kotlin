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

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.CancellationException

internal class ListMcpResourcesCoreTool(private val toolset: McpToolsetCore) :
  BaseTool("list_mcp_resources", DESCRIPTION) {
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = try {
    val page = toolset.listResources(context.context, args["cursor"] as? String)
    buildMap<String, Any> {
      put(
        "resources",
        page.resources.map { resource ->
          buildMap<String, Any> {
            put("name", resource.name)
            put("uri", resource.uri)
            resource.description?.let { put("description", it) }
            resource.mimeType?.let { put("mimeType", it) }
          }
        },
      )
      page.nextCursor?.let { put("nextCursor", it) }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    throw McpToolException.McpToolExecutionException(
      "Failed to list MCP resources: ${error.rootCauseMessage()}",
      error,
    )
  }

  override fun declaration(): FunctionDeclaration = cursorDeclaration(name, description)

  private companion object {
    const val DESCRIPTION =
      "List resources available on the MCP server. Returns one page of resources and, when more " +
        "pages are available, a 'nextCursor' value. To fetch the next page, call this tool again " +
        "passing that value as the 'cursor' argument; repeat until no 'nextCursor' is returned. " +
        "Each entry carries the resource's 'name' and 'uri': pass that 'uri' straight to " +
        "load_mcp_resource to read it, which avoids re-resolving the name."
  }
}

internal class ListMcpResourceTemplatesCoreTool(private val toolset: McpToolsetCore) :
  BaseTool("list_mcp_resource_templates", DESCRIPTION) {
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = try {
    val page = toolset.listResourceTemplates(context.context, args["cursor"] as? String)
    buildMap<String, Any> {
      put(
        "resourceTemplates",
        page.resourceTemplates.map { template ->
          buildMap<String, Any> {
            put("name", template.name)
            put("uriTemplate", template.uriTemplate)
            template.description?.let { put("description", it) }
            template.mimeType?.let { put("mimeType", it) }
          }
        },
      )
      page.nextCursor?.let { put("nextCursor", it) }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    throw McpToolException.McpToolExecutionException(
      "Failed to list MCP resource templates: ${error.rootCauseMessage()}",
      error,
    )
  }

  override fun declaration(): FunctionDeclaration = cursorDeclaration(name, description)

  private companion object {
    const val DESCRIPTION =
      "List resource templates available on the MCP server. Templates cover families of " +
        "resources that cannot be enumerated, so they never appear in list_mcp_resources. Each " +
        "entry has a 'uriTemplate' such as 'file:///{path}': substitute concrete values for the " +
        "{variables} yourself, then call load_mcp_resource with the resulting URI as its 'uri' " +
        "argument to read it."
  }
}

internal class LoadMcpResourceCoreTool(
  private val toolset: McpToolsetCore,
  private val maxLength: Int,
) : BaseTool("load_mcp_resource", DESCRIPTION) {
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val supplied = ARGUMENT_KEYS.filter(args::containsKey)
    if (supplied.size != 1) return wrongArgumentCountMessage(args, supplied)
    val key = supplied.single()
    val value = args[key] as? String ?: return notAStringMessage(key, args[key])
    var requestedUri = value
    return try {
      val uri =
        if (key == URI) value else {
          val matches = toolset.listAllResources(context.context).filter { it.name == value }
          when (matches.size) {
            0 -> return resourceNotFoundMessage(value)
            1 -> matches.single().uri
            else -> return ambiguousNameMessage(value, matches)
          }
        }
      requestedUri = uri
      toolset.readResource(context.context, uri).joinToString("\n\n") { content ->
        when (content) {
          is McpClientResourceContent.Text -> content.text.truncate(maxLength)
          is McpClientResourceContent.Blob ->
            "[Warning: Binary data found at this URI, cannot display raw content]"
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: McpResourceNotFoundException) {
      uriNotFoundMessage(requestedUri)
    } catch (error: Exception) {
      throw McpToolException.McpToolExecutionException(
        "Failed to load MCP resource: ${error.rootCauseMessage()}",
        error,
      )
    }
  }

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name,
      description,
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "name" to
              Schema(
                type = Type.STRING,
                description =
                  "The name of the resource to load, as returned by list_mcp_resources. Use " +
                    "this only when the name is all you have, since resolving it scans the " +
                    "full listing. Provide exactly one of 'name' or 'uri'.",
              ),
            "uri" to
              Schema(
                type = Type.STRING,
                description =
                  "The URI of the resource to load, and the preferred argument whenever you " +
                    "have one. It may come from the 'uri' field of a list_mcp_resources entry, " +
                    "from expanding a resource template, or from a resource link returned by " +
                    "another tool. Provide exactly one of 'name' or 'uri'.",
              ),
          ),
      required = emptyList(),
    ),
  )

  private fun wrongArgumentCountMessage(args: Map<String, Any?>, given: List<String>): String {
    val problem =
      if (given.isEmpty()) {
        "neither was given"
      } else {
        val malformed = given.filter { args[it] !is String }
        when {
          malformed.isEmpty() -> "both were given"
          malformed.size == given.size -> "both were given, and neither is a string"
          else -> "both were given, and " + malformed.joinToString { "\"$it\"" } + " is not a string"
        }
      }
    return "This tool takes exactly one of \"$NAME\" or \"$URI\", as a string, but $problem. " +
      "Use \"$NAME\" for a resource listed by list_mcp_resources, or \"$URI\" to read a " +
      "resource URI directly."
  }

  private fun notAStringMessage(key: String, value: Any?): String {
    val actual = value?.let { it::class.simpleName } ?: "null"
    return "The \"$key\" argument must be a string, but was $actual. " +
      "Use \"$NAME\" for a resource listed by list_mcp_resources, or \"$URI\" to read a " +
      "resource URI directly."
  }

  private fun uriNotFoundMessage(uri: String): String =
    "No resource at URI \"$uri\" on the MCP server. Check the URI, or call list_mcp_resources " +
      "to see what is available by name."

  private fun resourceNotFoundMessage(name: String): String =
    "No resource named \"$name\" is available on the MCP server. " +
      "Call list_mcp_resources to see the available resource names."

  private fun ambiguousNameMessage(name: String, matches: List<McpClientResource>): String {
    val candidates =
      matches.joinToString("\n") { resource ->
        buildString {
          append("- ")
          append(resource.uri)
          resource.description?.let { append(" - ").append(it) }
          resource.mimeType?.let { append(" [").append(it).append("]") }
        }
      }
    return "The name \"$name\" is ambiguous: ${matches.size} resources share it, so it cannot be " +
      "loaded by name. Pick one of these and call this tool again with its \"$URI\" argument:\n$candidates"
  }

  private companion object {
    const val NAME = "name"
    const val URI = "uri"
    val ARGUMENT_KEYS = listOf(NAME, URI)
    const val DESCRIPTION =
      "Load a resource from the MCP server. Provide exactly one of 'uri' or 'name'. Prefer " +
        "'uri' whenever you already have one, including the 'uri' that list_mcp_resources " +
        "returns for every entry; resolving a 'name' has to scan the whole resource listing. " +
        "Returns the resource as text: content over the size limit is truncated with a marker, " +
        "and binary content comes back as a short placeholder warning rather than the data."
  }
}

private fun cursorDeclaration(name: String, description: String): FunctionDeclaration =
  FunctionDeclaration(
    name,
    description,
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

private fun String.truncate(maxLength: Int): String =
  if (length <= maxLength) this else take(maxLength) + "... [Content truncated due to size limit]"

private fun Throwable.rootCauseMessage(): String? {
  var deepest = this
  while (deepest.cause != null) deepest = deepest.cause!!
  return deepest.message
}
