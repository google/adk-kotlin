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

/** SDK-neutral annotations carried by MCP resources and resource templates. */
internal data class McpClientAnnotations(
  val audience: List<String> = emptyList(),
  val priority: Double? = null,
  val lastModified: String? = null,
)

/** SDK-neutral resource metadata consumed by the shared MCP toolset core. */
internal data class McpClientResource(
  val name: String,
  val uri: String,
  val title: String? = null,
  val description: String? = null,
  val mimeType: String? = null,
  val size: Long? = null,
  val annotations: McpClientAnnotations? = null,
  val meta: Map<String, Any?>? = null,
)

/** One page of MCP resources. */
internal data class McpClientResourcePage(
  val resources: List<McpClientResource>,
  val nextCursor: String? = null,
)

/** SDK-neutral resource-template metadata consumed by the shared MCP toolset core. */
internal data class McpClientResourceTemplate(
  val name: String,
  val uriTemplate: String,
  val title: String? = null,
  val description: String? = null,
  val mimeType: String? = null,
  val annotations: McpClientAnnotations? = null,
  val meta: Map<String, Any?>? = null,
)

/** One page of MCP resource templates. */
internal data class McpClientResourceTemplatePage(
  val resourceTemplates: List<McpClientResourceTemplate>,
  val nextCursor: String? = null,
)

/** Content returned from MCP `resources/read`, without leaking an SDK content type. */
internal sealed interface McpClientResourceContent {
  val uri: String
  val mimeType: String?
  val meta: Map<String, Any?>?

  data class Text(
    override val uri: String,
    override val mimeType: String?,
    val text: String,
    override val meta: Map<String, Any?>? = null,
  ) : McpClientResourceContent

  data class Blob(
    override val uri: String,
    override val mimeType: String?,
    val blobBase64: String,
    override val meta: Map<String, Any?>? = null,
  ) : McpClientResourceContent
}

/** Optional extension of [McpClientSession] for servers that advertise MCP resources. */
internal interface McpResourceClientSession : McpClientSession {
  suspend fun listResources(cursor: String?): McpClientResourcePage

  suspend fun listResourceTemplates(cursor: String?): McpClientResourceTemplatePage

  suspend fun readResource(uri: String): List<McpClientResourceContent>
}

/** A server-declared missing resource; callers should report it without retrying the session. */
internal class McpResourceNotFoundException(uri: String, cause: Throwable) :
  RuntimeException("No MCP resource exists at URI: $uri", cause)
