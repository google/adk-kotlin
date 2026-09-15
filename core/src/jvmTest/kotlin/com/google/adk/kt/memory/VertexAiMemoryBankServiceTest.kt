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
import com.google.adk.kt.memory.dto.IngestEventsRequestDto
import com.google.adk.kt.memory.dto.MemoryDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesRequestDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesResponseDto
import com.google.adk.kt.memory.dto.RetrievedMemoryDto
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.VideoMetadata
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.io.IOException
import java.util.Date
import kotlin.test.assertFailsWith
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [VertexAiMemoryBankService], exercising the service-level routing (ingest vs
 * generate vs create), the generate/ingest config parsed from `customMetadata`, scope keys, fact
 * building, and result mapping against a fake [VertexAiMemoryBankClient]. Transport is covered by
 * `VertexAiMemoryBankClientTest`.
 */
@RunWith(JUnit4::class)
class VertexAiMemoryBankServiceTest {

  @Test
  fun addSessionToMemory_ingestsEventContentsWithScope() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)
    val session =
      Session(
        key = SessionKey("app", "user", "s1"),
        events =
          mutableListOf(
            textEvent("user", "hello", id = "e1", timestamp = 0L),
            Event(author = "model", content = null), // filtered out (no content)
          ),
      )

    service.addSessionToMemory(session)

    // No generate key present, so the default ingest path is used.
    assertThat(fake.generated).isEmpty()
    val request = fake.ingested.single()
    assertThat(request.scope).containsExactly("app_name", "app", "user_id", "user")
    val event = request.directContentsSource!!.events.single()
    assertThat(event.eventId).isEqualTo("e1")
    assertThat(event.eventTime).isEqualTo("1970-01-01T00:00:00Z")
  }

  @Test
  fun addSessionToMemory_sanitizesPartFieldsForWire() = runTest {
    // A model turn carries thought/thoughtSignature/partMetadata, which the deployed Memory Bank
    // endpoint rejects (HTTP 400 "Unknown name thoughtSignature"), so they must be stripped.
    // videoMetadata is a legitimate Content.Part field and must be kept.
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)
    val modelPart =
      Part(
        text = "answer",
        thought = true,
        thoughtSignature = byteArrayOf(1, -113, 61),
        videoMetadata = VideoMetadata(fps = 24.0),
        partMetadata = mapOf("internal" to "x"),
      )
    val session =
      Session(
        key = SessionKey("app", "user", "s1"),
        events =
          mutableListOf(Event(author = "model", content = Content(parts = listOf(modelPart)))),
      )

    service.addSessionToMemory(session)

    val wire = fake.ingested.single().directContentsSource!!.events.single().content.toString()
    assertThat(wire).contains("answer")
    assertThat(wire).contains("videoMetadata")
    assertThat(wire).doesNotContain("thought")
    assertThat(wire).doesNotContain("partMetadata")
  }

  @Test
  fun addEventsToMemory_ingestControlKeys_useIngestPathAndTravel() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory(
      appName = "app",
      userId = "user",
      events = listOf(textEvent("user", "hi")),
      customMetadata =
        mapOf(
          "stream_id" to "stream-1",
          "force_flush" to true,
          "generation_trigger_config" to mapOf("generation_rule" to mapOf("idle_duration" to "60s")),
        ),
    )

    assertThat(fake.generated).isEmpty()
    val request = fake.ingested.single()
    assertThat(request.streamId).isEqualTo("stream-1")
    assertThat(request.forceFlush).isTrue()
    assertThat(request.generationTriggerConfig.toString()).contains("idle_duration")
  }

  @Test
  fun addEventsToMemory_triggerConfigOnly_ingestsWithoutEvents() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory(
      appName = "app",
      userId = "user",
      events = emptyList(),
      customMetadata = mapOf("force_flush" to true),
    )

    val request = fake.ingested.single()
    assertThat(request.directContentsSource).isNull()
    assertThat(request.forceFlush).isTrue()
  }

  @Test
  fun addEventsToMemory_generateKey_routesToGenerateWithConfig() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory(
      appName = "app",
      userId = "user",
      events = listOf(textEvent("user", "hi")),
      customMetadata =
        mapOf(
          "disable_consolidation" to true,
          "disable_memory_revisions" to true,
          "revision_labels" to mapOf("source" to "chat"),
          "metadata_merge_strategy" to "MERGE",
          "metadata" to mapOf("topic" to "hobbies", "score" to 0.5, "flag" to true),
        ),
    )

    assertThat(fake.ingested).isEmpty()
    val request = fake.generated.single()
    assertThat(request.disableConsolidation).isTrue()
    assertThat(request.disableMemoryRevisions).isTrue()
    assertThat(request.revisionLabels).containsExactly("source", "chat")
    assertThat(request.metadataMergeStrategy).isEqualTo("MERGE")
    val metadata = request.metadata!!
    assertThat(metadata.getValue("topic").stringValue).isEqualTo("hobbies")
    assertThat(metadata.getValue("score").doubleValue).isEqualTo(0.5)
    assertThat(metadata.getValue("flag").boolValue).isTrue()
  }

  @Test
  fun addEventsToMemory_ttlAliasesRevisionTtl_explicitWins() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory(
      appName = "app",
      userId = "user",
      events = listOf(textEvent("user", "hi")),
      customMetadata = mapOf("ttl" to "10s"),
    )
    assertThat(fake.generated.single().revisionTtl).isEqualTo("10s")

    service.addEventsToMemory(
      appName = "app",
      userId = "user",
      events = listOf(textEvent("user", "hi")),
      customMetadata = mapOf("ttl" to "10s", "revision_ttl" to "20s"),
    )
    assertThat(fake.generated.last().revisionTtl).isEqualTo("20s")
  }

  @Test
  fun addEventsToMemory_nonBooleanForceFlush_throws() = runTest {
    val service = VertexAiMemoryBankService(FakeMemoryBankClient())
    assertFailsWith<IllegalArgumentException> {
      service.addEventsToMemory(
        appName = "app",
        userId = "user",
        events = listOf(textEvent("user", "hi")),
        customMetadata = mapOf("force_flush" to "yes"),
      )
    }
  }

  @Test
  fun addEventsToMemory_nonStringTtl_throws() = runTest {
    val service = VertexAiMemoryBankService(FakeMemoryBankClient())
    assertFailsWith<IllegalArgumentException> {
      service.addEventsToMemory(
        appName = "app",
        userId = "user",
        events = listOf(textEvent("user", "hi")),
        customMetadata = mapOf("ttl" to 5),
      )
    }
  }

  @Test
  fun addEventsToMemory_noUsableEvents_makesNoCall() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addEventsToMemory("app", "user", listOf(Event(author = "model", content = null)))

    assertThat(fake.ingested).isEmpty()
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
  fun addMemory_withConsolidation_generatesDirectMemoriesWithConfig() = runTest {
    val fake = FakeMemoryBankClient()
    val service = VertexAiMemoryBankService(fake)

    service.addMemory(
      "app",
      "user",
      listOf(memoryEntry("fact-1"), memoryEntry("fact-2")),
      customMetadata = mapOf("enable_consolidation" to true, "ttl" to "6000s"),
    )

    assertThat(fake.created).isEmpty()
    val request = fake.generated.single()
    assertThat(request.directMemoriesSource!!.directMemories.map { it.fact })
      .containsExactly("fact-1", "fact-2")
      .inOrder()
    assertThat(request.revisionTtl).isEqualTo("6000s")
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
  fun constructor_rejectsNonNumericAgentEngineId() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiMemoryBankService(
        project = "p",
        location = "l",
        agentEngineId = "not-numeric",
        credentials = fakeCredentials(),
      )
    }
  }

  @Test
  fun constructor_invalidInput_closesHttpClient() {
    val httpClient = HttpClient(Java)
    assertFailsWith<IllegalArgumentException> {
      VertexAiMemoryBankService(
        project = "p",
        location = "l",
        agentEngineId = "not-numeric",
        credentials = fakeCredentials(),
        httpClient = httpClient,
      )
    }
    assertThat(httpClient.isActive).isFalse()
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
    val ingested = mutableListOf<IngestEventsRequestDto>()
    val created = mutableListOf<MemoryDto>()
    var lastRetrieve: RetrieveMemoriesRequestDto? = null

    override suspend fun generateMemories(request: GenerateMemoriesRequestDto): Result<Unit> {
      failure?.let {
        return Result.failure(it)
      }
      generated.add(request)
      return Result.success(Unit)
    }

    override suspend fun ingestEvents(request: IngestEventsRequestDto): Result<Unit> {
      failure?.let {
        return Result.failure(it)
      }
      ingested.add(request)
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
    fun textEvent(author: String, text: String, id: String = "e1", timestamp: Long = 0L): Event =
      Event(
        id = id,
        author = author,
        timestamp = timestamp,
        content = Content(role = Role.USER, parts = listOf(Part(text = text))),
      )

    fun memoryEntry(text: String): MemoryEntry =
      MemoryEntry(content = Content(role = Role.USER, parts = listOf(Part(text = text))))

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(AccessToken("fake-token", Date(Long.MAX_VALUE)))
        .build()
  }
}
