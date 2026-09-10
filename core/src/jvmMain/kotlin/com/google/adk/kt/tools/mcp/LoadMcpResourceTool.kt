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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.McpToolException.McpToolExecutionException
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import io.modelcontextprotocol.spec.McpError
import kotlinx.coroutines.CancellationException

/**
 * A built-in tool that allows the ADK agents to load resources exposed by the MCP server.
 *
 * Requires `useMcpResources = true` in the `McpToolset` configuration.
 */
internal class LoadMcpResourceTool(
  private val mcpToolset: McpToolset,
  private val maxMcpResourceLength: Int,
) : BaseTool("load_mcp_resource", DESCRIPTION) {
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    try {
      // Presence first, type second. Collapsing the two would let a malformed value pass as an
      // absent one: {"name": "x", "uri": 123} would quietly read by name, which is the guessing
      // this tool exists to avoid, and {"name": 1, "uri": 2} would be reported as "both given"
      // when the real fault is the types.
      //
      // Returned, not thrown: a thrown tool exception aborts the whole agent turn (and cancels
      // sibling tool calls) without the model ever seeing why, so a malformed call could never be
      // corrected. Every caller mistake here comes back as text the model can act on.
      val given = ARGUMENT_KEYS.filter { args.containsKey(it) }
      if (given.size != 1) {
        return wrongArgumentCountMessage(args, given)
      }
      val key = given.single()
      val value = args[key] as? String ?: return notAStringMessage(key, args[key])

      val readonlyContext = context.invocationContext.toReadonlyContext()

      // A uri is the resource's identity, so it is read straight through. Only a name needs
      // resolving, and only that path pays for the full listing.
      val resolvedUri =
        if (key == URI) {
          value
        } else {
          val name = value
          // Resolve the name against the full listing so collisions are detected reliably.
          val listing =
            try {
              mcpToolset.listAllResources(readonlyContext)
            } catch (e: McpError) {
              // A refused listing leaves the name unresolvable, which the model can act on.
              logger.warn {
                "MCP server rejected a resource listing with code ${e.jsonRpcError?.code()}."
              }
              return listingRejectedMessage(e.message ?: "no reason given")
            }
          logger.debug { "Scanned ${listing.size} MCP resources to resolve name \"$name\"." }
          val matches = listing.filter { it.name == name }
          when (matches.size) {
            0 -> {
              logger.warn { "No MCP resource named \"$name\" in a listing of ${listing.size}." }
              return resourceNotFoundMessage(name)
            }
            1 -> matches.single().uri
            else -> {
              logger.warn { "MCP resource name \"$name\" matched ${matches.size} resources." }
              return ambiguousNameMessage(name, matches)
            }
          }
        }

      // A rejected uri is a caller mistake like an unknown name, so it comes back as text too.
      // An McpError is the server's own error response, whatever code it chose; a transport
      // failure is not one and still throws.
      val contents =
        try {
          mcpToolset.readResource(resolvedUri, readonlyContext)
        } catch (e: McpError) {
          logger.warn { "MCP server rejected a resource read with code ${e.jsonRpcError?.code()}." }
          return uriRejectedMessage(resolvedUri, e.message ?: "no reason given")
        }
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

  /**
   * Names every fault at once, so the model can retry instead of guessing.
   *
   * Reporting only the count would cost a round trip when the values are also malformed: the model
   * drops one argument, resends, and only then learns the survivor was never a string.
   */
  private fun wrongArgumentCountMessage(args: Map<String, Any?>, given: List<String>): String {
    val problem =
      if (given.isEmpty()) {
        "neither was given"
      } else {
        val malformed = given.filter { args[it] !is String }
        when {
          malformed.isEmpty() -> "both were given"
          malformed.size == given.size -> "both were given, and neither is a string"
          else ->
            "both were given, and " + malformed.joinToString { "\"$it\"" } + " is not a string"
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

  /** Carries the server's own reason, since the code alone no longer identifies a not-found. */
  private fun uriRejectedMessage(uri: String, reason: String): String =
    "The MCP server rejected the read of URI \"$uri\": $reason. Check the URI, or call " +
      "list_mcp_resources to see what is available by name."

  private fun listingRejectedMessage(reason: String): String =
    "The MCP server rejected the resource listing needed to resolve a name: $reason. Retry with " +
      "the resource's \"$URI\" if you have one."

  private fun resourceNotFoundMessage(name: String): String =
    "No resource named \"$name\" is available on the MCP server. " +
      "Call list_mcp_resources to see the available resource names."

  private fun ambiguousNameMessage(name: String, matches: List<McpResourceInfo>): String {
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
      "loaded by name. Pick one of these and call this tool again with its \"$URI\" argument:" +
      "\n$candidates"
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
                    "The name of the resource to load, as returned by list_mcp_resources. Use " +
                      "this only when the name is all you have, since resolving it scans the " +
                      "full listing. Provide exactly one of 'name' or 'uri'.",
                ),
              URI to
                Schema(
                  type = Type.STRING,
                  description =
                    "The URI of the resource to load, and the preferred argument whenever you " +
                      "have one. It may come from the 'uri' field of a list_mcp_resources entry, " +
                      "from expanding a resource template, or from a resource link returned by " +
                      "another tool. Provide exactly one of 'name' or 'uri'.",
                ),
            ),
          // Exactly one of the two is required, which JSON Schema cannot express here, so the
          // constraint lives in the descriptions above and is enforced in run().
          required = emptyList(),
        ),
    )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LoadMcpResourceTool::class)

    private const val NAME = "name"
    private const val URI = "uri"

    private val ARGUMENT_KEYS = listOf(NAME, URI)

    private const val DESCRIPTION =
      "Load a resource from the MCP server. Provide exactly one of 'uri' or 'name'. Prefer " +
        "'uri' whenever you already have one, including the 'uri' that list_mcp_resources " +
        "returns for every entry; resolving a 'name' has to scan the whole resource listing. " +
        "Returns the resource as text: content over the size limit is truncated with a marker, " +
        "and binary content comes back as a short placeholder warning rather than the data."
  }
}
