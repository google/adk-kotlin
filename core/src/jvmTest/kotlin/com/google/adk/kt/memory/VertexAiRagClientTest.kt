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

package com.google.adk.kt.memory

import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.memory.dto.RagQueryDto
import com.google.adk.kt.memory.dto.RagResourceDto
import com.google.adk.kt.memory.dto.RetrieveContextsRequestDto
import com.google.adk.kt.memory.dto.VertexRagStoreDto
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Transport-level tests for [VertexAiRagClient]. These drive a real [GoogleApiClient] (with a fake,
 * non-expiring credential) against a [MockWebServer], so they exercise URL construction, header
 * injection, JSON (de)serialization, the multipart upload body, and the error-handling contract end
 * to end.
 */
@RunWith(JUnit4::class)
class VertexAiRagClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: VertexAiRagClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client =
      VertexAiRagClient(
        apiClient = GoogleApiClient(credentials = fakeCredentials()),
        project = "test-project",
        location = "test-location",
        baseUrlOverride = server.url("/").toString(),
      )
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun retrieveContexts_sendsRequestAndParsesResponse() {
    server.enqueue(
      jsonResponse(
        """
        {"contexts":{"contexts":[
          {"text":"line-1","sourceDisplayName":"adk-memory-v1.a.b.c","score":0.1},
          {"text":"line-2","sourceDisplayName":"other"}
        ]}}
        """
          .trimIndent()
      )
    )

    val response = runBlocking {
      client.retrieveContexts(
        RetrieveContextsRequestDto(
          vertexRagStore =
            VertexRagStoreDto(
              ragResources =
                listOf(
                  RagResourceDto(
                    ragCorpus = "projects/test-project/locations/test-location/ragCorpora/corpus-1"
                  )
                ),
              vectorDistanceThreshold = 10.0,
            ),
          query = RagQueryDto(text = "hello", similarityTopK = 3),
        )
      )
    }

    val contexts = response.getOrThrow()!!.contexts!!.contexts
    assertThat(contexts.map { it.text }).containsExactly("line-1", "line-2").inOrder()
    assertThat(contexts[0].sourceDisplayName).isEqualTo("adk-memory-v1.a.b.c")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.target)
      .isEqualTo("/v1beta1/projects/test-project/locations/test-location:retrieveContexts")
    assertThat(request.headers.values("Authorization").firstOrNull()).isEqualTo("Bearer fake-token")
    assertThat(request.headers.values("Content-Type").firstOrNull()).contains("application/json")
    val body = request.body?.utf8()
    assertThat(body)
      .contains(
        "\"ragCorpus\":\"projects/test-project/locations/test-location/ragCorpora/corpus-1\""
      )
    assertThat(body).contains("\"vectorDistanceThreshold\":10")
    assertThat(body).contains("\"text\":\"hello\"")
    assertThat(body).contains("\"similarityTopK\":3")
  }

  @Test
  fun retrieveContexts_notFound_returnsSuccessNull() = runBlocking {
    server.enqueue(MockResponse(code = 404))

    assertThat(client.retrieveContexts(minimalRequest()).getOrThrow()).isNull()
  }

  @Test
  fun retrieveContexts_serverError_returnsFailure() {
    server.enqueue(MockResponse(code = 500, body = "boom"))

    val result = runBlocking { client.retrieveContexts(minimalRequest()) }

    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun retrieveContexts_clientError_returnsFailure() {
    // A non-404 client error must surface as a failure, not be swallowed as an empty result.
    server.enqueue(MockResponse(code = 403, body = "denied"))

    val result = runBlocking { client.retrieveContexts(minimalRequest()) }

    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun uploadRagFile_sendsMultipartToUploadEndpoint() {
    server.enqueue(jsonResponse("""{"ragFile":{"displayName":"adk-memory-v1.a.b.c"}}"""))

    val result = runBlocking {
      client.uploadRagFile(
        corpusName = "projects/test-project/locations/test-location/ragCorpora/corpus-1",
        displayName = "adk-memory-v1.a.b.c",
        content = "the-file-content",
      )
    }
    assertThat(result.isSuccess).isTrue()

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.target)
      .isEqualTo(
        "/upload/v1beta1/projects/test-project/locations/test-location/ragCorpora/corpus-1/ragFiles:upload"
      )
    assertThat(request.headers.values("Authorization").firstOrNull()).isEqualTo("Bearer fake-token")
    assertThat(request.headers.values("X-Goog-Upload-Protocol").firstOrNull())
      .isEqualTo("multipart")
    assertThat(request.headers.values("Content-Type").firstOrNull()).contains("multipart/form-data")
    val body = request.body?.utf8()
    // Both multipart parts are present, and they carry the metadata and file payloads.
    assertThat(body).contains("name=metadata")
    assertThat(body).contains("name=file")
    assertThat(body).contains("\"displayName\":\"adk-memory-v1.a.b.c\"")
    assertThat(body).contains("the-file-content")
  }

  @Test
  fun uploadRagFile_serverError_returnsFailure() {
    server.enqueue(MockResponse(code = 500, body = "boom"))

    val result = runBlocking {
      client.uploadRagFile("projects/p/locations/l/ragCorpora/c", "dn", "body")
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  private companion object {
    fun minimalRequest(): RetrieveContextsRequestDto =
      RetrieveContextsRequestDto(
        vertexRagStore = VertexRagStoreDto(ragResources = listOf(RagResourceDto(ragCorpus = "c"))),
        query = RagQueryDto(text = "q"),
      )

    fun jsonResponse(body: String): MockResponse =
      MockResponse(headers = Headers.headersOf("Content-Type", "application/json"), body = body)

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(
          AccessToken("fake-token", Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()))
        )
        .build()
  }
}
