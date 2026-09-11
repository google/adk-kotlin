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

package com.google.adk.kt.tools

import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.memory.VertexAiRagClient
import com.google.adk.kt.memory.dto.RagContextDto
import com.google.adk.kt.memory.dto.RagContextsDto
import com.google.adk.kt.memory.dto.RetrieveContextsRequestDto
import com.google.adk.kt.memory.dto.RetrieveContextsResponseDto
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.Type
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VertexAiRagClientToolTest {

  @Test
  fun run_withQuery_returnsContextTextsAndBuildsRequest() = runBlocking {
    val fake =
      FakeVertexAiRagClient(
        Result.success(
          RetrieveContextsResponseDto(
            contexts = RagContextsDto(contexts = listOf(context("alpha"), context("beta")))
          )
        )
      )
    val tool = tool(fake, vectorDistanceThreshold = 0.5)

    val result = tool.run(testToolContext(), mapOf("query" to "hello", "similarityTopK" to 3))

    assertEquals(listOf("alpha", "beta"), result)
    val request = fake.lastRequest!!
    assertEquals(CORPUS_NAME, request.vertexRagStore.ragResources?.single()?.ragCorpus)
    assertEquals(0.5, request.vertexRagStore.vectorDistanceThreshold)
    assertEquals("hello", request.query.text)
    assertEquals(3, request.query.similarityTopK)
  }

  @Test
  fun run_similarityTopKAndThresholdOmitted_sendNulls() = runBlocking {
    val fake = FakeVertexAiRagClient(Result.success(RetrieveContextsResponseDto()))
    val tool = tool(fake)

    val result = tool.run(testToolContext(), mapOf("query" to "hello"))

    assertTrue(result is String)
    assertNull(fake.lastRequest!!.query.similarityTopK)
    assertNull(fake.lastRequest!!.vertexRagStore.vectorDistanceThreshold)
  }

  @Test
  fun run_noMatchingContexts_returnsInformativeString() = runBlocking {
    val fake =
      FakeVertexAiRagClient(
        Result.success(
          RetrieveContextsResponseDto(contexts = RagContextsDto(contexts = emptyList()))
        )
      )
    val tool = tool(fake)

    val result = tool.run(testToolContext(), mapOf("query" to "hello"))

    assertTrue(result is String && result.startsWith("No matching result"))
  }

  @Test
  fun run_clientFailure_propagates() {
    val fake = FakeVertexAiRagClient(Result.failure(IOException("boom")))
    val tool = tool(fake)

    assertFailsWith<IOException> {
      runBlocking { tool.run(testToolContext(), mapOf("query" to "hello")) }
    }
  }

  @Test
  fun run_missingQuery_returnsErrorMap() = runBlocking {
    val fake = FakeVertexAiRagClient(Result.success(RetrieveContextsResponseDto()))
    val tool = tool(fake)

    val result = tool.run(testToolContext(), emptyMap())

    assertTrue(result is Map<*, *>)
    assertEquals("INVALID_ARGUMENTS", result["error_code"])
    assertNull(fake.lastRequest) // Never reaches the client.
  }

  @Test
  fun run_blankQuery_returnsErrorMap() = runBlocking {
    val fake = FakeVertexAiRagClient(Result.success(RetrieveContextsResponseDto()))
    val tool = tool(fake)

    val result = tool.run(testToolContext(), mapOf("query" to "   "))

    assertTrue(result is Map<*, *>)
    assertEquals("INVALID_ARGUMENTS", result["error_code"])
    assertNull(fake.lastRequest)
  }

  @Test
  fun declaration_exposesRequiredQueryAndOptionalSimilarityTopK() {
    val declaration = tool(FakeVertexAiRagClient(Result.success(null))).declaration()

    val properties = declaration.parameters?.properties.orEmpty()
    assertEquals(Type.STRING, properties["query"]?.type)
    assertEquals(Type.INTEGER, properties["similarityTopK"]?.type)
    assertEquals(listOf("query"), declaration.parameters?.required)
  }

  @Test
  fun publicConstructor_rejectsFullResourceNameAsCorpus() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiRagClientTool(
        name = "rag",
        description = "Retrieves docs.",
        project = "p",
        location = "l",
        ragCorpus = "projects/p/locations/l/ragCorpora/c",
        credentials = fakeCredentials(),
      )
    }
  }

  /** A [VertexAiRagClient] that captures the request and returns a canned result. */
  private class FakeVertexAiRagClient(private val result: Result<RetrieveContextsResponseDto?>) :
    VertexAiRagClient(GoogleApiClient(credentials = fakeCredentials()), "p", "l") {
    var lastRequest: RetrieveContextsRequestDto? = null

    override suspend fun retrieveContexts(
      request: RetrieveContextsRequestDto
    ): Result<RetrieveContextsResponseDto?> {
      lastRequest = request
      return result
    }
  }

  private companion object {
    const val CORPUS_NAME = "projects/p/locations/l/ragCorpora/c"

    fun tool(
      client: VertexAiRagClient,
      vectorDistanceThreshold: Double? = null,
    ): VertexAiRagClientTool =
      VertexAiRagClientTool(
        name = "rag",
        description = "Retrieves docs.",
        client = client,
        corpusName = CORPUS_NAME,
        vectorDistanceThreshold = vectorDistanceThreshold,
      )

    fun context(text: String): RagContextDto = RagContextDto(text = text)

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(
          AccessToken("fake-token", Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()))
        )
        .build()
  }
}
