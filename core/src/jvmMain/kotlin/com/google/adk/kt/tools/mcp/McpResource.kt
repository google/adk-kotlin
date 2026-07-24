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

/**
 * A resource advertised by an MCP server, as returned by [McpToolset.listResources].
 *
 * This is the ADK-owned view of a `resources/list` entry; it does not leak the MCP SDK type.
 *
 * @property name The server-assigned name of the resource. Not guaranteed to be unique across the
 *   server, so [uri] (not [name]) is the identifier to pass to [McpToolset.readResource].
 * @property uri The canonical, unambiguous identifier for the resource. MCP treats this as an
 *   opaque, server-interpreted token, so it is kept as the exact string the server returned.
 * @property description An optional human-readable description of the resource.
 * @property mimeType The MIME type of the resource, if the server declared one.
 */
data class McpResourceInfo(
  val name: String,
  val uri: String,
  val description: String? = null,
  val mimeType: String? = null,
)

/**
 * A single page of resources returned by [McpToolset.listResources].
 *
 * @property resources The resources on this page.
 * @property nextCursor An opaque cursor for fetching the next page, or `null` if this is the last
 *   page. Pass it back to [McpToolset.listResources] to continue paginating.
 */
data class McpResourceListing(val resources: List<McpResourceInfo>, val nextCursor: String? = null)

/**
 * A resource template advertised by an MCP server, as returned by
 * [McpToolset.listResourceTemplates].
 *
 * Unlike [McpResourceInfo], a template carries a [uriTemplate] (an RFC 6570 URI template such as
 * `file:///{path}`) rather than a concrete URI: it must be expanded with variables before it names
 * a readable resource, so it is intentionally *not* interchangeable with [McpResourceInfo.uri].
 *
 * @property name The server-assigned name of the template.
 * @property uriTemplate The RFC 6570 URI template used to construct concrete resource URIs.
 * @property description An optional human-readable description of the template.
 * @property mimeType The MIME type shared by resources matching this template, if declared.
 */
data class McpResourceTemplateInfo(
  val name: String,
  val uriTemplate: String,
  val description: String? = null,
  val mimeType: String? = null,
)

/**
 * A single page of resource templates returned by [McpToolset.listResourceTemplates].
 *
 * @property resourceTemplates The templates on this page.
 * @property nextCursor An opaque cursor for fetching the next page, or `null` if this is the last
 *   page. Pass it back to [McpToolset.listResourceTemplates] to continue paginating.
 */
data class McpResourceTemplateListing(
  val resourceTemplates: List<McpResourceTemplateInfo>,
  val nextCursor: String? = null,
)

/**
 * The contents of a resource read from an MCP server, as returned by [McpToolset.readResource].
 *
 * A resource resolves to either text ([Text]) or binary data ([Blob]); this sealed type models both
 * so callers handle them exhaustively without leaking the MCP SDK type.
 */
sealed interface McpResourceContent {
  /** The URI of the resource these contents came from. */
  val uri: String

  /** The MIME type of the contents, if the server declared one. */
  val mimeType: String?

  /**
   * Text contents of a resource.
   *
   * @property text The text of the resource.
   */
  data class Text(override val uri: String, override val mimeType: String?, val text: String) :
    McpResourceContent

  /**
   * Binary contents of a resource.
   *
   * @property blobBase64 The binary data of the resource, base64-encoded exactly as the server
   *   returned it.
   */
  data class Blob(
    override val uri: String,
    override val mimeType: String?,
    val blobBase64: String,
  ) : McpResourceContent
}
