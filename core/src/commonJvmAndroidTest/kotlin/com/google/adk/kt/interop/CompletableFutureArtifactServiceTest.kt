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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class CompletableFutureArtifactServiceTest {

  private val key = SessionKey("app", "user", "session")
  private val artifacts = CompletableFutureArtifactService.of(InMemoryArtifactService())

  @Test
  fun saveArtifact_completesWithEachNewVersion() {
    val first = artifacts.saveArtifact(key, "report.txt", Part(text = "draft")).get(5, SECONDS)
    val second = artifacts.saveArtifact(key, "report.txt", Part(text = "final")).get(5, SECONDS)

    assertThat(first).isEqualTo(0)
    assertThat(second).isEqualTo(1)
    assertThat(artifacts.listVersions(key, "report.txt").get(5, SECONDS))
      .containsExactly(0, 1)
      .inOrder()
  }

  @Test
  fun loadArtifact_withoutVersion_loadsTheLatestVersion() {
    artifacts.saveArtifact(key, "report.txt", Part(text = "draft")).get(5, SECONDS)
    artifacts.saveArtifact(key, "report.txt", Part(text = "final")).get(5, SECONDS)

    assertThat(artifacts.loadArtifact(key, "report.txt").get(5, SECONDS)?.text).isEqualTo("final")
    assertThat(artifacts.loadArtifact(key, "report.txt", 0).get(5, SECONDS)?.text)
      .isEqualTo("draft")
  }

  @Test
  fun loadArtifact_missingArtifact_completesWithNull() {
    assertThat(artifacts.loadArtifact(key, "missing.txt").get(5, SECONDS)).isNull()
  }

  @Test
  fun saveAndReloadArtifact_completesWithTheSavedArtifact() {
    val reloaded =
      artifacts.saveAndReloadArtifact(key, "report.txt", Part(text = "draft")).get(5, SECONDS)

    assertThat(reloaded.text).isEqualTo("draft")
    assertThat(artifacts.listArtifactKeys(key).get(5, SECONDS)).containsExactly("report.txt")
  }

  @Test
  fun deleteArtifact_removesTheArtifact() {
    artifacts.saveArtifact(key, "report.txt", Part(text = "draft")).get(5, SECONDS)

    artifacts.deleteArtifact(key, "report.txt").get(5, SECONDS)

    assertThat(artifacts.listArtifactKeys(key).get(5, SECONDS)).isEmpty()
  }

  @Test
  fun close_cancelsCallsInFlightAndLaterCalls(): Unit = runBlocking {
    val hanging = HangingArtifactService()
    val wrapper = CompletableFutureArtifactService.of(hanging)
    val inFlight = wrapper.listArtifactKeys(key)
    hanging.started.await()

    wrapper.close()
    val later = wrapper.listArtifactKeys(key)

    withTimeout(5.seconds) { hanging.canceled.await() }
    assertFailsWith<CancellationException> { inFlight.get(5, SECONDS) }
    assertFailsWith<CancellationException> { later.get(5, SECONDS) }
    assertThat(hanging.calls.get()).isEqualTo(1)
  }

  @Test
  fun close_autoCloseableService_closesTheService() {
    var closed = false
    val closeable =
      object : ArtifactService by InMemoryArtifactService(), AutoCloseable {
        override fun close() {
          closed = true
        }
      }

    CompletableFutureArtifactService.of(closeable).close()

    assertThat(closed).isTrue()
  }

  /** An artifact service whose [listArtifactKeys] suspends until it is canceled. */
  private class HangingArtifactService : ArtifactService by InMemoryArtifactService() {
    val calls = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val canceled = CompletableDeferred<Unit>()

    override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> {
      calls.incrementAndGet()
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        canceled.complete(Unit)
      }
    }
  }
}
