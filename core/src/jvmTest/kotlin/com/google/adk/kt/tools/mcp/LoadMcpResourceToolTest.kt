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

import com.google.adk.kt.testing.testToolContext
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LoadMcpResourceToolTest {

  private fun createMcpToolset(mockMcpSession: McpAsyncClient): McpToolset {
    // The toolset fetches the pooled session from the manager; hand it the mock session.
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }
    return McpToolset(mockSessionManager)
  }

  private fun resource(name: String, uri: String, mimeType: String? = null): McpSchema.Resource {
    val builder = McpSchema.Resource.builder(uri, name)
    mimeType?.let { builder.mimeType(it) }
    return builder.build()
  }

  /** Stubs the full resource listing returned by the server. */
  private fun stubResources(session: McpAsyncClient, vararg resources: McpSchema.Resource) {
    // One page, no nextCursor. listAllResources now follows cursors itself, so a mock can drive
    // real multi-page behavior; see run_resolvesNameToUri_acrossMultiplePages.
    whenever(session.listResources(isNull())) doReturn
      mono { McpSchema.ListResourcesResult.builder(resources.toList()).build() }
  }

  @Test
  fun run_withTextContents_returnsConcatenatedText() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    val contents =
      listOf(
        McpSchema.TextResourceContents.builder("uri1", "Part 1 ").mimeType("text/plain").build(),
        McpSchema.TextResourceContents.builder("uri1", "Part 2").mimeType("text/plain").build(),
      )
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult.builder(contents).build() }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    assertThat(result).isEqualTo("Part 1 \n\nPart 2")
  }

  @Test
  fun run_resolvesNameToUri_againstTheWholeListing() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    // The target is not the first resource: the tool must scan the whole listing to resolve it.
    stubResources(mockMcpSession, resource("other", "uriOther"), resource("target", "uriTarget"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono {
        McpSchema.ReadResourceResult.builder(
            listOf(
              McpSchema.TextResourceContents.builder("uriTarget", "hello")
                .mimeType("text/plain")
                .build()
            )
          )
          .build()
      }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "target"))

    assertThat(result).isEqualTo("hello")
    // The resolved URI (not the name) is what gets read from the server.
    val captor = argumentCaptor<McpSchema.ReadResourceRequest>()
    verify(mockMcpSession).readResource(captor.capture())
    assertThat(captor.firstValue.uri()).isEqualTo("uriTarget")

    // The scan drives the cursor overload itself rather than the SDK's unbounded aggregating one.
    verify(mockMcpSession).listResources(isNull())
    verify(mockMcpSession, never()).listResources()
  }

  @Test
  fun run_resolvesNameToUri_acrossMultiplePages() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = LoadMcpResourceTool(createMcpToolset(mockMcpSession), maxMcpResourceLength = 1000)

    // The target is on page 2, so a scan that stopped after page 1 would report it as not found.
    whenever(mockMcpSession.listResources(isNull())) doReturn
      mono {
        McpSchema.ListResourcesResult.builder(listOf(resource("other", "uriOther")))
          .nextCursor("page-2")
          .build()
      }
    whenever(mockMcpSession.listResources("page-2")) doReturn
      mono {
        McpSchema.ListResourcesResult.builder(listOf(resource("target", "uriTarget"))).build()
      }
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono {
        McpSchema.ReadResourceResult.builder(
            listOf(
              McpSchema.TextResourceContents.builder("uriTarget", "page two")
                .mimeType("text/plain")
                .build()
            )
          )
          .build()
      }

    assertThat(tool.run(testToolContext(), mapOf("name" to "target"))).isEqualTo("page two")

    val captor = argumentCaptor<McpSchema.ReadResourceRequest>()
    verify(mockMcpSession).readResource(captor.capture())
    assertThat(captor.firstValue.uri()).isEqualTo("uriTarget")
  }

  @Test
  fun run_boundsTheScan_whenTheServerNeverStopsPaginating() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = LoadMcpResourceTool(createMcpToolset(mockMcpSession), maxMcpResourceLength = 1000)

    // A server handing back a constant cursor: without a cap this scans until the process dies,
    // and it is reachable from a model-chosen tool call.
    whenever(mockMcpSession.listResources(anyOrNull<String>())) doReturn
      mono {
        McpSchema.ListResourcesResult.builder(listOf(resource("other", "uriOther")))
          .nextCursor("same-cursor")
          .build()
      }

    assertFailsWith<McpToolException.McpToolExecutionException> {
      tool.run(testToolContext(), mapOf("name" to "target"))
    }

    // Bounded, and it never got as far as reading anything.
    verify(mockMcpSession, times(100)).listResources(anyOrNull<String>())
    verify(mockMcpSession, never()).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_withTextContents_truncatesWhenExceedingMax() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 5)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    val contents =
      listOf(
        McpSchema.TextResourceContents.builder("uri1", "HelloWorld").mimeType("text/plain").build()
      )
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult.builder(contents).build() }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    val resultStr = result as String
    assertThat(resultStr).startsWith("Hello")
    assertThat(resultStr).contains("[Content truncated due to size limit]")
  }

  @Test
  fun run_withBlobContents_returnsWarning() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    val contents =
      listOf(
        McpSchema.BlobResourceContents.builder("uri1", "binary_data_base64")
          .mimeType("application/octet-stream")
          .build()
      )
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult.builder(contents).build() }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    val resultStr = result as String
    assertThat(resultStr)
      .contains("[Warning: Binary data found at this URI, cannot display raw content]")
  }

  @Test
  fun run_withNoContents_returnsEmptyString() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult.builder(emptyList()).build() }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    assertThat(result).isEqualTo("")
  }

  @Test
  fun run_unknownName_returnsNotFoundMessage() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "missing")) as String

    assertThat(result).contains("No resource named")
    assertThat(result).contains("missing")
    assertThat(result).contains("list_mcp_resources")
    verify(mockMcpSession, never()).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_ambiguousName_returnsErrorListingCandidates() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("dup", "uriA"), resource("dup", "uriB"))

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "dup")) as String

    assertThat(result).contains("ambiguous")
    assertThat(result).contains("uriA")
    assertThat(result).contains("uriB")
    // An ambiguous name must never be read: it cannot be resolved to a single URI.
    verify(mockMcpSession, never()).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_withNeitherNameNorUri_returnsMessageNamingTheMistake() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    // A returned message, never a throw: a thrown tool exception aborts the agent turn without
    // the model seeing why, so it could never correct the call.
    val result = tool.run(testToolContext(), emptyMap())

    assertThat(result.toString()).contains("neither was given")
    assertThat(result.toString()).contains("list_mcp_resources")
  }

  @Test
  fun run_withNonStringName_returnsMessageNamingTheMistake() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val result = tool.run(testToolContext(), mapOf("name" to 42))

    assertThat(result.toString()).contains("\"name\" argument must be a string")
    assertThat(result.toString()).contains("was Int")
  }

  @Test
  fun run_withBothKeysPresent_doesNotGuessWhenOneIsMalformed() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = LoadMcpResourceTool(createMcpToolset(mockMcpSession), maxMcpResourceLength = 1000)
    stubResources(mockMcpSession, resource("res1", "uri1"))

    // Both keys are present but one is malformed. Gating on nullness rather than presence would
    // read this as "only name was given" and quietly resolve by name, which is the guessing this
    // tool exists to avoid.
    val result = tool.run(testToolContext(), mapOf("name" to "res1", "uri" to 123))

    assertThat(result.toString()).contains("both were given")
    assertThat(result.toString()).contains("\"uri\" is not a string")
    verify(mockMcpSession, never()).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_withBothArgumentsMalformed_reportsCountAndTypesTogether() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = LoadMcpResourceTool(createMcpToolset(mockMcpSession), maxMcpResourceLength = 1000)

    // Both faults in one message: fixing the count alone would still fail on the types.
    val result = tool.run(testToolContext(), mapOf("name" to 1, "uri" to 2))

    assertThat(result.toString()).contains("both were given")
    assertThat(result.toString()).contains("neither is a string")
  }

  @Test
  fun run_withUri_readsDirectlyWithoutScanningTheCatalog() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    // Deliberately not in resources/list: a uri expanded from a template, or one handed over by a
    // resource link. Resolving by name could never reach it.
    whenever(
      mockMcpSession.readResource(
        McpSchema.ReadResourceRequest.builder("file:///reports/q3.txt").build()
      )
    ) doReturn
      mono {
        McpSchema.ReadResourceResult.builder(
            listOf(
              McpSchema.TextResourceContents.builder("file:///reports/q3.txt", "q3 data")
                .mimeType("text/plain")
                .build()
            )
          )
          .build()
      }

    val result = tool.run(testToolContext(), mapOf("uri" to "file:///reports/q3.txt"))

    assertThat(result).isEqualTo("q3 data")
    // The catalog scan is the expensive path; a uri must not pay for it.
    verify(mockMcpSession, never()).listResources()
  }

  @Test
  fun run_withUriTheServerRejects_returnsMessageInsteadOfThrowing() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = LoadMcpResourceTool(createMcpToolset(mockMcpSession), maxMcpResourceLength = 1000)

    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { throw McpError.RESOURCE_NOT_FOUND.apply("mem://doc/nope") }

    // A wrong uri is a caller mistake, like an unknown name. Throwing would abort the turn and
    // tell the model nothing, and uri is exactly where a hand-expanded template goes wrong.
    val result = tool.run(testToolContext(), mapOf("uri" to "mem://doc/nope"))

    assertThat(result.toString()).contains("mem://doc/nope")
    assertThat(result.toString()).contains("list_mcp_resources")
    // Not retried: a rejected request is not a dead session.
    verify(mockMcpSession, times(1)).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_withUriAndTransportFailure_stillThrows() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = LoadMcpResourceTool(createMcpToolset(mockMcpSession), maxMcpResourceLength = 1000)

    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { throw IllegalStateException("transport went away") }

    // Only not-found becomes text; a broken transport must still surface as a failure.
    assertFailsWith<McpToolException.McpToolExecutionException> {
      tool.run(testToolContext(), mapOf("uri" to "mem://doc/nope"))
    }
  }

  @Test
  fun run_withBothNameAndUri_returnsMessageNamingTheMistake() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val result = tool.run(testToolContext(), mapOf("name" to "res1", "uri" to "uri1"))

    assertThat(result.toString()).contains("both were given")
    // Nothing was attempted against the server.
    verify(mockMcpSession, never()).listResources()
  }

  @Test
  fun run_throwsMcpToolExecutionExceptionOnFailure() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { throw RuntimeException("Server error") }

    val context = testToolContext()

    val ex =
      assertFailsWith<McpToolException.McpToolExecutionException> {
        tool.run(context, mapOf("name" to "res1"))
      }
    assertThat(ex.message).contains("Server error")
  }

  @Test
  fun declaration_returnsCorrectSchema() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val declaration = tool.declaration()

    assertThat(declaration).isNotNull()
    assertThat(declaration.name).isEqualTo("load_mcp_resource")
    // The declaration, not just the error messages, has to carry the contract: the model's first
    // call is shaped by this text.
    assertThat(declaration.description).contains("exactly one of 'uri' or 'name'")
    assertThat(declaration.description).contains("Prefer 'uri'")
    assertThat(declaration.description).contains("truncated")
    assertThat(declaration.description).contains("binary")

    val properties = declaration.parameters?.properties
    assertThat(properties?.keys).containsExactly("name", "uri")
    // Exactly-one-of cannot be expressed in the schema, so neither is statically required.
    assertThat(declaration.parameters?.required).isEmpty()
  }
}
