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

import com.google.adk.kt.events.Event
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.memory.dto.GenerateMemoriesRequestDto
import com.google.adk.kt.memory.dto.MemoryDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesRequestDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesResponseDto
import com.google.adk.kt.memory.dto.RetrievedMemoryDto
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.Date
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [VertexAiMemoryBankService], exercising the service-level routing (generate vs
 * create, consolidation), scope keys, fact building, and result mapping against a fake
 * [VertexAiMemoryBankClient]. Transport is covered by `VertexAiMemoryBankClientTest`.
 */
@RunWith(JUnit4::class)
class VertexAiMemoryBankServiceTest {

  @Test
  fun addSessionToMemory_generatesFromEventContentsWithScope() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)
    val session =
      Session(
        key = SessionKey("app", "user", "s1"),
        events =
          mutableListOf(
            textEvent("user", "hello"),
            Event(author = "model", content = null), // filtered out (no content)
          ),
      )

    service.addSessionToMemory(session)

    val request = fake.generated.single()
    assertThat(request.scope).containsExactly("app_name", "app", "user_id", "user")
    assertThat(request.directContentsSource!!.events).hasSize(1)
    assertThat(request.directMemoriesSource).isNull()
  }

  @Test
  fun addSessionToMemory_dropsModelInternalPartFields() = runTest {
    // A model turn carries thought/thoughtSignature; Memory Bank's Content proto rejects them
    // (HTTP 400 "Unknown name thoughtSignature"), so only content-bearing fields must be sent.
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)
    val modelPart =
      Part(text = "answer", thought = true, thoughtSignature = byteArrayOf(1, -113, 61))
    val session =
      Session(
        key = SessionKey("app", "user", "s1"),
        events =
          mutableListOf(Event(author = "model", content = Content(parts = listOf(modelPart)))),
      )

    service.addSessionToMemory(session)

    val wire = fake.generated.single().directContentsSource!!.events.single().content.toString()
    assertThat(wire).contains("answer")
    assertThat(wire).doesNotContain("thoughtSignature")
    assertThat(wire).doesNotContain("thought")
  }

  @Test
  fun addEventsToMemory_passesDisableConsolidation() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory(
      appName = "app",
      userId = "user",
      events = listOf(textEvent("user", "hi")),
      customMetadata = mapOf("disable_consolidation" to true),
    )

    assertThat(fake.generated.single().disableConsolidation).isTrue()
  }

  @Test
  fun addEventsToMemory_noUsableEvents_makesNoCall() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory("app", "user", listOf(Event(author = "model", content = null)))

    assertThat(fake.generated).isEmpty()
  }

  @Test
  fun addMemory_default_createsOneMemoryPerEntry() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addMemory(
      "app",
      "user",
      listOf(memoryEntry("Likes hiking."), memoryEntry("Allergic to peanuts.")),
    )

    assertThat(fake.created.map { it.fact })
      .containsExactly("Likes hiking.", "Allergic to peanuts.")
      .inOrder()
    assertThat(fake.created.first().scope).containsExactly("app_name", "app", "user_id", "user")
    assertThat(fake.generated).isEmpty()
  }

  @Test
  fun addMemory_withConsolidation_generatesDirectMemories() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addMemory(
      "app",
      "user",
      listOf(memoryEntry("fact-1"), memoryEntry("fact-2")),
      customMetadata = mapOf("enable_consolidation" to true),
    )

    assertThat(fake.created).isEmpty()
    val request = fake.generated.single()
    assertThat(request.directMemoriesSource!!.directMemories.map { it.fact })
      .containsExactly("fact-1", "fact-2")
      .inOrder()
  }

  @Test
  fun addMemory_consolidation_batchesInGroupsOfFive() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addMemory(
      "app",
      "user",
      (1..7).map { memoryEntry("fact-$it") },
      customMetadata = mapOf("enable_consolidation" to true),
    )

    // 7 memories -> two generate calls (5 + 2).
    assertThat(fake.generated).hasSize(2)
    assertThat(fake.generated[0].directMemoriesSource!!.directMemories).hasSize(5)
    assertThat(fake.generated[1].directMemoriesSource!!.directMemories).hasSize(2)
  }

  @Test
  fun addMemory_empty_throws() = runTest {
    val service = VertexAiMemoryBankService(FakeMemoryBankClient())
    assertFailsWith<IllegalArgumentException> { service.addMemory("app", "user", emptyList()) }
  }

  @Test
  fun addMemory_nonTextMemory_throws() = runTest {
    val service = VertexAiMemoryBankService(FakeMemoryBankClient())
    val blank = MemoryEntry(content = Content(role = Role.USER, parts = listOf(Part(text = "   "))))
    assertFailsWith<IllegalArgumentException> { service.addMemory("app", "user", listOf(blank)) }
  }

  @Test
  fun searchMemory_mapsMemoriesAndScope() = runTest {
    val fake =
      FakeMemoryBankClient(
        retrieveResponse =
          RetrieveMemoriesResponseDto(
            retrievedMemories =
              listOf(
                RetrievedMemoryDto(
                  memory = MemoryDto(fact = "Likes hiking.", updateTime = "2026-07-17T00:00:00Z")
                ),
                RetrievedMemoryDto(memory = MemoryDto(fact = "")), // skipped (empty fact)
                RetrievedMemoryDto(memory = null), // skipped (no memory)
              )
          )
      )
    val service = VertexAiMemoryBankService(fake)

    val result = service.searchMemory("app", "user", "what do they like")

    assertThat(result.memories).hasSize(1)
    val entry = result.memories.single()
    assertThat(entry.content.parts.first().text).isEqualTo("Likes hiking.")
    assertThat(entry.author).isEqualTo("user")
    assertThat(entry.timestamp).isEqualTo("2026-07-17T00:00:00Z")
    assertThat(fake.lastRetrieve!!.scope).containsExactly("app_name", "app", "user_id", "user")
    assertThat(fake.lastRetrieve!!.similaritySearchParams!!.searchQuery)
      .isEqualTo("what do they like")
  }

  @Test
  fun validateAgentEngineId_acceptsNumericIdAndRejectsOthers() {
    assertThat(VertexAiMemoryBankService.validateAgentEngineId("456")).isEqualTo("456")
    assertFailsWith<IllegalArgumentException> {
      VertexAiMemoryBankService.validateAgentEngineId("  ")
    }
    // A full resource name (or any non-digit id) is rejected; pass project/location separately.
    assertFailsWith<IllegalArgumentException> {
      VertexAiMemoryBankService.validateAgentEngineId("projects/p/locations/l/reasoningEngines/456")
    }
  }

  @Test
  fun validateSegment_allowsSafeValuesAndRejectsPathEscapes() {
    assertThat(VertexAiMemoryBankService.validateSegment("my-project_1", "project"))
      .isEqualTo("my-project_1")
    assertThat(VertexAiMemoryBankService.validateSegment("global", "location")).isEqualTo("global")
    // Anything that could escape the URL path segment is rejected.
    for (bad in listOf("a/b", "a..b", "a?b", "a#b", "a b", "")) {
      assertFailsWith<IllegalArgumentException> {
        VertexAiMemoryBankService.validateSegment(bad, "project")
      }
    }
  }

  @Test
  fun searchMemory_propagatesClientFailure() = runTest {
    val service = VertexAiMemoryBankService(FakeMemoryBankClient(failure = IOException("boom")))
    assertFailsWith<IOException> { service.searchMemory("app", "user", "q") }
  }

  @Test
  fun addSessionToMemory_propagatesClientFailure() = runTest {
    val service = VertexAiMemoryBankService(FakeMemoryBankClient(failure = IOException("boom")))
    assertFailsWith<IOException> {
      service.addSessionToMemory(
        Session(
          key = SessionKey("app", "user", "s1"),
          events = mutableListOf(textEvent("user", "hi")),
        )
      )
    }
  }

  /**
   * A [VertexAiMemoryBankClient] that records write requests and returns a canned retrieve response
   * or, when [failure] is set, a [Result.failure].
   */
  private class FakeMemoryBankClient(
    private val retrieveResponse: RetrieveMemoriesResponseDto? = null,
    private val failure: Throwable? = null,
  ) : VertexAiMemoryBankClient(GoogleApiClient(credentials = fakeCredentials()), "p", "l", "e") {

    val generated = mutableListOf<GenerateMemoriesRequestDto>()
    val created = mutableListOf<MemoryDto>()
    var lastRetrieve: RetrieveMemoriesRequestDto? = null

    override suspend fun generateMemories(request: GenerateMemoriesRequestDto): Result<Unit> {
      failure?.let {
        return Result.failure(it)
      }
      generated.add(request)
      return Result.success(Unit)
    }

    override suspend fun createMemory(memory: MemoryDto): Result<Unit> {
      failure?.let {
        return Result.failure(it)
      }
      created.add(memory)
      return Result.success(Unit)
    }

    override suspend fun retrieveMemories(
      request: RetrieveMemoriesRequestDto
    ): Result<RetrieveMemoriesResponseDto?> {
      lastRetrieve = request
      return failure?.let { Result.failure(it) } ?: Result.success(retrieveResponse)
    }
  }

  private companion object {
    fun textEvent(author: String, text: String): Event =
      Event(author = author, content = Content(role = Role.USER, parts = listOf(Part(text = text))))

    fun memoryEntry(text: String): MemoryEntry =
      MemoryEntry(content = Content(role = Role.USER, parts = listOf(Part(text = text))))

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(AccessToken("fake-token", Date(Long.MAX_VALUE)))
        .build()
  }
}
