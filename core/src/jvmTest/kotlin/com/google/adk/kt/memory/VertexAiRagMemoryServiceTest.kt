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
import com.google.adk.kt.memory.dto.RagContextDto
import com.google.adk.kt.memory.dto.RagContextsDto
import com.google.adk.kt.memory.dto.RetrieveContextsRequestDto
import com.google.adk.kt.memory.dto.RetrieveContextsResponseDto
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.Base64
import java.util.Date
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [VertexAiRagMemoryService].
 *
 * These exercise the service-level logic (display-name encoding, event serialization, client-side
 * scope filtering, chunk merging/sorting, and corpus-name handling) against a fake
 * [VertexAiRagClient], so no HTTP transport is involved. The transport itself is covered by
 * `VertexAiRagClientTest`.
 */
@RunWith(JUnit4::class)
class VertexAiRagMemoryServiceTest {

  private val corpus = "projects/p/locations/l/ragCorpora/c"

  @Test
  fun addSessionToMemory_uploadsEncodedDisplayNameAndTextEvents() = runTest {
    val fake = FakeRagClient()
    val service =
      VertexAiRagMemoryService(fake, corpus, similarityTopK = 5, vectorDistanceThreshold = 10.0)
    val session =
      Session(
        key = SessionKey("app", "user", "sess"),
        events =
          mutableListOf(
            textEvent("user", 1500L, "hello"),
            emptyEvent("model", 1600L),
            textEvent("model", 1700L, "world"),
          ),
      )

    service.addSessionToMemory(session)

    assertThat(fake.uploadedCorpus).isEqualTo(corpus)
    assertThat(fake.uploadedDisplayName).isEqualTo(displayName("app", "user", "sess"))
    // Only the two events with text produce a line; timestamps are written in seconds.
    val lines = fake.uploadedContent!!.split("\n")
    assertThat(lines).hasSize(2)
    assertThat(lines[0]).contains("\"author\":\"user\"")
    assertThat(lines[0]).contains("\"text\":\"hello\"")
    assertThat(lines[0]).contains("\"timestamp\":1.5")
    assertThat(lines[1]).contains("\"text\":\"world\"")
  }

  @Test
  fun addSessionToMemory_flattensNewlinesAndJoinsPartsWithDot() = runTest {
    val fake = FakeRagClient()
    val service = VertexAiRagMemoryService(fake, corpus, null, 10.0)
    val event =
      Event(
        author = "user",
        content = Content(parts = listOf(Part(text = "line-1\nline-2"), Part(text = "part-2"))),
        timestamp = 1000L,
      )

    service.addSessionToMemory(
      Session(key = SessionKey("app", "user", "sess"), events = mutableListOf(event))
    )

    assertThat(fake.uploadedContent).contains("\"text\":\"line-1 line-2.part-2\"")
  }

  @Test
  fun addSessionToMemory_missingSessionId_throws() = runTest {
    val service = VertexAiRagMemoryService(FakeRagClient(), corpus, null, 10.0)

    assertFailsWith<IllegalArgumentException> {
      service.addSessionToMemory(Session(key = SessionKey("app", "user", null)))
    }
  }

  @Test
  fun searchMemory_sendsCorpusQueryAndKeepsOnlyMatchingScope() = runTest {
    val fake =
      FakeRagClient(
        response =
          response(
            // Matches app/user; two events out of order to verify sorting.
            RagContextDto(
              sourceDisplayName = displayName("app", "user", "s1"),
              text = line("user", 2.0, "second") + "\n" + line("user", 1.0, "first"),
            ),
            // Different app: filtered out.
            RagContextDto(
              sourceDisplayName = displayName("other-app", "user", "s2"),
              text = line("user", 1.0, "nope"),
            ),
            // Unparseable display name: filtered out.
            RagContextDto(sourceDisplayName = "garbage", text = line("user", 1.0, "nope")),
          )
      )
    val service =
      VertexAiRagMemoryService(fake, corpus, similarityTopK = 7, vectorDistanceThreshold = 4.0)

    val result = service.searchMemory("app", "user", "the-query")

    assertThat(result.memories.map { it.content.parts.first().text })
      .containsExactly("first", "second")
      .inOrder()
    assertThat(result.memories.first().author).isEqualTo("user")
    // The request carries the normalized corpus, top-k, distance threshold and query text.
    val request = fake.lastRequest!!
    assertThat(request.vertexRagStore.ragResources!!.single().ragCorpus).isEqualTo(corpus)
    assertThat(request.vertexRagStore.vectorDistanceThreshold).isEqualTo(4.0)
    assertThat(request.query.text).isEqualTo("the-query")
    assertThat(request.query.similarityTopK).isEqualTo(7)
  }

  @Test
  fun scopeSurvivesUploadAndSearch_forIdsContainingDotsAndNonAscii() = runTest {
    // The base64 display-name encoding exists so ids with `.` (which is the part separator) and
    // non-ASCII survive the round trip. Upload with such ids, then feed the produced display name
    // back through search and confirm the scope still matches.
    val app = "my.app"
    val user = "user.é"
    val session = "s.1"
    val uploader = FakeRagClient()
    VertexAiRagMemoryService(uploader, corpus, null, 10.0)
      .addSessionToMemory(
        Session(
          key = SessionKey(app, user, session),
          events = mutableListOf(textEvent("user", 1000L, "hi")),
        )
      )

    val searcher =
      FakeRagClient(
        response =
          response(
            RagContextDto(
              sourceDisplayName = uploader.uploadedDisplayName,
              text = line("user", 1.0, "hi"),
            )
          )
      )
    val result = VertexAiRagMemoryService(searcher, corpus, null, 10.0).searchMemory(app, user, "q")

    assertThat(result.memories.map { it.content.parts.first().text }).containsExactly("hi")
  }

  @Test
  fun searchMemory_acceptsLegacyDottedDisplayName() = runTest {
    val fake =
      FakeRagClient(
        response =
          response(
            // Legacy, non-prefixed 3-part display name (raw, unencoded).
            RagContextDto(sourceDisplayName = "app.user.s1", text = line("user", 1.0, "legacy"))
          )
      )

    val result = VertexAiRagMemoryService(fake, corpus, null, 10.0).searchMemory("app", "user", "q")

    assertThat(result.memories.map { it.content.parts.first().text }).containsExactly("legacy")
  }

  @Test
  fun searchMemory_convertsTimestampSecondsToIsoMillis() = runTest {
    val fake =
      FakeRagClient(
        response =
          response(
            RagContextDto(
              sourceDisplayName = displayName("app", "user", "s1"),
              text = line("user", 1.5, "hello"),
            )
          )
      )

    val result = VertexAiRagMemoryService(fake, corpus, null, 10.0).searchMemory("app", "user", "q")

    // 1.5s on the wire -> 1500ms -> ISO-8601 UTC.
    assertThat(result.memories.single().timestamp).isEqualTo("1970-01-01T00:00:01.500Z")
  }

  @Test
  fun searchMemory_skipsMalformedJsonLines() = runTest {
    val fake =
      FakeRagClient(
        response =
          response(
            RagContextDto(
              sourceDisplayName = displayName("app", "user", "s1"),
              text = "not-json\n" + line("user", 1.0, "good") + "\n{broken",
            )
          )
      )

    val result = VertexAiRagMemoryService(fake, corpus, null, 10.0).searchMemory("app", "user", "q")

    assertThat(result.memories.map { it.content.parts.first().text }).containsExactly("good")
  }

  @Test
  fun searchMemory_mergesOverlappingChunksFromSameSession() = runTest {
    val fake =
      FakeRagClient(
        response =
          response(
            RagContextDto(
              sourceDisplayName = displayName("app", "user", "s1"),
              text = line("user", 1.0, "a") + "\n" + line("user", 2.0, "b"),
            ),
            // Same session, overlaps at timestamp 2.0, so it is merged and de-duplicated.
            RagContextDto(
              sourceDisplayName = displayName("app", "user", "s1"),
              text = line("user", 2.0, "b") + "\n" + line("user", 3.0, "c"),
            ),
          )
      )
    val service = VertexAiRagMemoryService(fake, corpus, null, 10.0)

    val result = service.searchMemory("app", "user", "q")

    assertThat(result.memories.map { it.content.parts.first().text })
      .containsExactly("a", "b", "c")
      .inOrder()
  }

  @Test
  fun searchMemory_emptyResponse_returnsNoMemories() = runTest {
    val service = VertexAiRagMemoryService(FakeRagClient(response = null), corpus, null, 10.0)

    assertThat(service.searchMemory("app", "user", "q").memories).isEmpty()
  }

  @Test
  fun normalizeCorpusName_expandsBareId() {
    assertThat(VertexAiRagMemoryService.normalizeCorpusName("my-corpus", "proj", "loc"))
      .isEqualTo("projects/proj/locations/loc/ragCorpora/my-corpus")
  }

  @Test
  fun normalizeCorpusName_rejectsFullResourceName() {
    // Only a bare id is accepted; a full resource name must be rejected.
    assertFailsWith<IllegalArgumentException> {
      VertexAiRagMemoryService.normalizeCorpusName(
        "projects/proj/locations/loc/ragCorpora/z",
        "proj",
        "loc",
      )
    }
  }

  @Test
  fun normalizeCorpusName_rejectsIdsThatEscapeThePathSegment() {
    // A bare id must stay within one URL path segment (no `/`, `?`, `#`, or `..`).
    for (bad in listOf("a/b", "a..b", "a?b", "a#b", "a b")) {
      assertFailsWith<IllegalArgumentException> {
        VertexAiRagMemoryService.normalizeCorpusName(bad, "proj", "loc")
      }
    }
  }

  @Test
  fun searchMemory_propagatesClientFailure() = runTest {
    val service =
      VertexAiRagMemoryService(FakeRagClient(failure = IOException("boom")), corpus, null, 10.0)

    assertFailsWith<IOException> { service.searchMemory("app", "user", "q") }
  }

  @Test
  fun addSessionToMemory_propagatesClientFailure() = runTest {
    val service =
      VertexAiRagMemoryService(FakeRagClient(failure = IOException("boom")), corpus, null, 10.0)

    assertFailsWith<IOException> {
      service.addSessionToMemory(
        Session(
          key = SessionKey("app", "user", "sess"),
          events = mutableListOf(textEvent("user", 1L, "hi")),
        )
      )
    }
  }

  /**
   * A [VertexAiRagClient] that records uploads and returns a canned retrieve response or failure.
   */
  private class FakeRagClient(
    private val response: RetrieveContextsResponseDto? = null,
    private val failure: Throwable? = null,
  ) : VertexAiRagClient(GoogleApiClient(credentials = fakeCredentials()), "p", "l") {

    var uploadedCorpus: String? = null
    var uploadedDisplayName: String? = null
    var uploadedContent: String? = null
    var lastRequest: RetrieveContextsRequestDto? = null

    override suspend fun uploadRagFile(
      corpusName: String,
      displayName: String,
      content: String,
    ): Result<Unit> {
      failure?.let {
        return Result.failure(it)
      }
      uploadedCorpus = corpusName
      uploadedDisplayName = displayName
      uploadedContent = content
      return Result.success(Unit)
    }

    override suspend fun retrieveContexts(
      request: RetrieveContextsRequestDto
    ): Result<RetrieveContextsResponseDto?> {
      lastRequest = request
      return failure?.let { Result.failure(it) } ?: Result.success(response)
    }
  }

  private companion object {
    private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()

    fun textEvent(author: String, timestamp: Long, text: String): Event =
      Event(
        author = author,
        content = Content(parts = listOf(Part(text = text))),
        timestamp = timestamp,
      )

    fun emptyEvent(author: String, timestamp: Long): Event =
      Event(author = author, content = Content(parts = emptyList()), timestamp = timestamp)

    fun displayName(appName: String, userId: String, sessionId: String): String =
      "adk-memory-v1." +
        listOf(appName, userId, sessionId).joinToString(".") {
          URL_ENCODER.encodeToString(it.toByteArray(Charsets.UTF_8))
        }

    fun line(author: String, timestampSeconds: Double, text: String): String =
      """{"author":"$author","timestamp":$timestampSeconds,"text":"$text"}"""

    fun response(vararg contexts: RagContextDto): RetrieveContextsResponseDto =
      RetrieveContextsResponseDto(contexts = RagContextsDto(contexts = contexts.toList()))

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(AccessToken("fake-token", Date(Long.MAX_VALUE)))
        .build()
  }
}
