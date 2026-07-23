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
import com.google.adk.kt.memory.dto.DirectContentsSourceDto
import com.google.adk.kt.memory.dto.DirectContentsSourceEventDto
import com.google.adk.kt.memory.dto.GenerateMemoriesRequestDto
import com.google.adk.kt.memory.dto.MemoryDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesRequestDto
import com.google.adk.kt.memory.dto.SimilaritySearchParamsDto
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Transport-level tests for [VertexAiMemoryBankClient]: URL construction, headers, request bodies,
 * response parsing, and the error contract, driven through a [MockWebServer].
 */
@RunWith(JUnit4::class)
class VertexAiMemoryBankClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: VertexAiMemoryBankClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client =
      VertexAiMemoryBankClient(
        apiClient = GoogleApiClient(credentials = fakeCredentials()),
        project = "test-project",
        location = "test-location",
        agentEngineId = "456",
        baseUrlOverride = server.url("/").toString(),
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun generateMemories_postsToGenerateEndpoint() {
    server.enqueue(jsonResponse("""{"name":"operations/1"}"""))

    val result = runBlocking {
      client.generateMemories(
        GenerateMemoriesRequestDto(
          scope = mapOf("app_name" to "app", "user_id" to "user"),
          directContentsSource =
            DirectContentsSourceDto(
              events =
                listOf(
                  DirectContentsSourceEventDto(
                    content = JsonObject(mapOf("role" to JsonPrimitive("user")))
                  )
                )
            ),
        )
      )
    }
    assertThat(result.isSuccess).isTrue()

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path)
      .isEqualTo(
        "/v1beta1/projects/test-project/locations/test-location/reasoningEngines/456/memories:generate"
      )
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer fake-token")
    val body = request.body.readUtf8()
    assertThat(body).contains("\"app_name\":\"app\"")
    assertThat(body).contains("\"directContentsSource\"")
  }

  @Test
  fun createMemory_postsMemoryAsBody() {
    server.enqueue(jsonResponse("""{"name":"operations/2"}"""))

    val result = runBlocking {
      client.createMemory(
        MemoryDto(fact = "Likes hiking.", scope = mapOf("app_name" to "app", "user_id" to "user"))
      )
    }
    assertThat(result.isSuccess).isTrue()

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path)
      .isEqualTo(
        "/v1beta1/projects/test-project/locations/test-location/reasoningEngines/456/memories"
      )
    val body = request.body.readUtf8()
    assertThat(body).contains("\"fact\":\"Likes hiking.\"")
    assertThat(body).contains("\"app_name\":\"app\"")
  }

  @Test
  fun retrieveMemories_postsAndParsesResponse() {
    server.enqueue(
      jsonResponse(
        """
        {"retrievedMemories":[
          {"memory":{"fact":"Likes hiking.","updateTime":"2026-07-17T00:00:00Z"},"distance":0.1}
        ]}
        """
          .trimIndent()
      )
    )

    val response = runBlocking {
      client.retrieveMemories(
        RetrieveMemoriesRequestDto(
          scope = mapOf("app_name" to "app", "user_id" to "user"),
          similaritySearchParams = SimilaritySearchParamsDto(searchQuery = "hobbies"),
        )
      )
    }

    assertThat(response.getOrThrow()!!.retrievedMemories.single().memory!!.fact)
      .isEqualTo("Likes hiking.")
    val request = server.takeRequest()
    assertThat(request.path)
      .isEqualTo(
        "/v1beta1/projects/test-project/locations/test-location/reasoningEngines/456/memories:retrieve"
      )
    assertThat(request.body.readUtf8()).contains("\"searchQuery\":\"hobbies\"")
  }

  @Test
  fun retrieveMemories_notFound_returnsSuccessNull() {
    server.enqueue(MockResponse().setResponseCode(404))

    val result = runBlocking {
      client.retrieveMemories(RetrieveMemoriesRequestDto(scope = mapOf("app_name" to "app")))
    }

    assertThat(result.getOrThrow()).isNull()
  }

  @Test
  fun retrieveMemories_clientError_returnsFailure() {
    // A non-404 client error must surface as a failure, not be swallowed as an empty result.
    server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))

    val result = runBlocking {
      client.retrieveMemories(RetrieveMemoriesRequestDto(scope = mapOf("app_name" to "app")))
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun retrieveMemories_serverError_returnsFailure() {
    server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

    val result = runBlocking {
      client.retrieveMemories(RetrieveMemoriesRequestDto(scope = mapOf("app_name" to "app")))
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun generateMemories_serverError_returnsFailure() {
    server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

    val result = runBlocking {
      client.generateMemories(GenerateMemoriesRequestDto(scope = mapOf("app_name" to "app")))
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  private companion object {
    fun jsonResponse(body: String): MockResponse =
      MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(
          AccessToken("fake-token", Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()))
        )
        .build()
  }
}
