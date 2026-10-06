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

package com.google.adk.kt.agents

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.storedBytes
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Covers the recording that `RunConfig.saveLiveBlob` turns on. */
class AudioCacheManagerTest {

  private val artifacts = InMemoryArtifactService()

  private fun context(agentName: String = "weather_agent") =
    testInvocationContext(agent = DummyAgent(name = agentName), artifactService = artifacts)

  private fun pcm(vararg bytes: Byte) =
    Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(*bytes))

  @Test
  fun flush_writesTheModelAudioAsOneArtifactReference() = runBlocking {
    val context = context()
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1, 2))
    manager.cacheOutput(pcm(3))

    val events = manager.flush(flushUserAudio = false, flushModelAudio = true)

    val event = events.single()
    val fileData = event.content?.parts?.single()?.fileData
    assertNotNull(fileData, "the session gets a reference, not the bytes")
    assertTrue(
      fileData.fileUri?.contains("/_adk_live/adk_live_audio_storage_output_audio_") == true,
      "unexpected artifact reference",
    )
    assertEquals("audio/pcm;rate=24000", fileData.mimeType, "mime comes from the first chunk")
  }

  @Test
  fun flush_dropsMimeParametersFromTheFilename() = runBlocking {
    // Python strips MIME parameters from the saved filename while keeping them on the mime type.
    val context = context()
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1, 2))

    val fileData =
      manager
        .flush(flushUserAudio = false, flushModelAudio = true)
        .single()
        .content
        ?.parts
        ?.single()
        ?.fileData

    assertNotNull(fileData, "the session gets a reference")
    assertTrue(
      fileData.fileUri?.substringBefore('#')?.endsWith(".pcm") == true,
      "the filename extension drops the mime parameters: ${fileData.fileUri}",
    )
    assertEquals("audio/pcm;rate=24000", fileData.mimeType, "the mime type keeps its parameters")
  }

  @Test
  fun flush_joinsTheChunksInOrder() = runBlocking {
    // The point of the recording: one playable file, not a pile of packets.
    val context = context()
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1, 2))
    manager.cacheOutput(pcm(3))
    manager.cacheOutput(pcm(4, 5))

    val events = manager.flush(flushUserAudio = false, flushModelAudio = true)

    assertContentEquals(
      byteArrayOf(1, 2, 3, 4, 5),
      artifacts.storedBytes(events.single(), context.session.key),
    )
  }

  @Test
  fun flush_authorsModelAudioWithTheAgentName() = runBlocking {
    // The model's audio is authored by this agent, not the role, or the session misattributes it.
    val manager = AudioCacheManager(context(agentName = "weather_agent"))
    manager.cacheOutput(pcm(1))

    val event = manager.flush(flushUserAudio = false, flushModelAudio = true).single()

    assertEquals("weather_agent", event.author)
    assertEquals(Role.MODEL, event.content?.role)
  }

  @Test
  fun flush_authorsUserAudioWithTheRole() = runBlocking {
    // The other half of the same rule: the caller's audio has no agent to name.
    val manager = AudioCacheManager(context())
    manager.cacheInput(pcm(1))

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertEquals(Role.USER, event.author)
    assertEquals(Role.USER, event.content?.role)
  }

  @Test
  fun flush_onInterruption_leavesTheCallersRecordingRunning() = runBlocking {
    // An interruption ends the model's turn only; the user's recording continues.
    val manager = AudioCacheManager(context())
    manager.cacheInput(pcm(1))
    manager.cacheOutput(pcm(2))

    val interruption = manager.flush(flushUserAudio = false, flushModelAudio = true)
    val laterTurnComplete = manager.flush(flushUserAudio = true, flushModelAudio = true)

    assertEquals(1, interruption.size, "only the model's audio is written on an interruption")
    assertEquals(1, laterTurnComplete.size, "the caller's audio survived to the turn boundary")
    assertEquals(Role.USER, laterTurnComplete.single().author)
  }

  @Test
  fun flush_withNothingCached_writesNothing() = runBlocking {
    val manager = AudioCacheManager(context())

    assertEquals(emptyList(), manager.flush(flushUserAudio = true, flushModelAudio = true))
  }

  @Test
  fun flush_afterAFlush_doesNotRewriteTheSameAudio() = runBlocking {
    // The cache is emptied as it is taken, so a second turn does not re-save the first one's audio.
    val manager = AudioCacheManager(context())
    manager.cacheOutput(pcm(1))
    val unused = manager.flush(flushUserAudio = false, flushModelAudio = true)

    assertEquals(emptyList(), manager.flush(flushUserAudio = false, flushModelAudio = true))
  }

  /** One mebibyte of audio, so a test can reach the cap in a handful of chunks. */
  private fun oneMebibyte(mime: String = "audio/pcm;rate=24000") =
    Blob(mimeType = mime, data = ByteArray(1024 * 1024))

  /** Chunks of [oneMebibyte] that exactly fill the 8 MiB cap without exceeding it. */
  private val chunksToFillTheCap = 8

  @Test
  fun cache_pastTheCap_stopsAcceptingChunks() = runBlocking {
    // Sustained interruption: no turn completes, so without a bound the input cache never drains.
    val context = context()
    val manager = AudioCacheManager(context)
    repeat(chunksToFillTheCap + 4) { manager.cacheInput(oneMebibyte()) }

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertEquals(
      chunksToFillTheCap * 1024 * 1024,
      artifacts.storedBytes(event, context.session.key)?.size,
      "the cache kept accepting chunks past its cap",
    )
  }

  @Test
  fun cache_pastTheCap_keepsThePrefixRatherThanTheTail() = runBlocking {
    // Not a ring buffer: dropping the head would misstate the file's start time and mime type.
    val context = context()
    val manager = AudioCacheManager(context)
    manager.cacheInput(oneMebibyte(mime = "audio/pcm;rate=16000"))
    repeat(chunksToFillTheCap + 4) {
      manager.cacheInput(oneMebibyte(mime = "audio/pcm;rate=24000"))
    }

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertEquals(
      chunksToFillTheCap * 1024 * 1024,
      artifacts.storedBytes(event, context.session.key)?.size,
      "the cache kept accepting chunks past its cap",
    )
    assertEquals(
      "audio/pcm;rate=16000",
      event.content?.parts?.single()?.fileData?.mimeType,
      "the first chunk was dropped, so the artifact misreports where the recording starts",
    )
  }

  @Test
  fun cache_pastTheCap_rejectsEvenASmallerLaterChunk() = runBlocking {
    // Once capped, even a smaller chunk is rejected so the recording has no gap after the cap.
    val context = context()
    val manager = AudioCacheManager(context)
    repeat(chunksToFillTheCap - 1) { manager.cacheInput(oneMebibyte()) }
    // Two more mebibytes do not fit, so the cache is now capped at the 7 MiB prefix.
    manager.cacheInput(Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(2 * 1024 * 1024)))
    // A tiny chunk would fit under the running total, but a capped cache still rejects it.
    manager.cacheInput(pcm(1, 2, 3))

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertEquals(
      (chunksToFillTheCap - 1) * 1024 * 1024,
      artifacts.storedBytes(event, context.session.key)?.size,
      "a chunk after the cap left a gap instead of a clean prefix",
    )
  }

  @Test
  fun cache_underTheCap_isUnaffected() = runBlocking {
    // The bound is a safety net and must not shorten normal recordings.
    val context = context()
    val manager = AudioCacheManager(context)
    repeat(chunksToFillTheCap - 1) { manager.cacheInput(oneMebibyte()) }

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertEquals(
      (chunksToFillTheCap - 1) * 1024 * 1024,
      artifacts.storedBytes(event, context.session.key)?.size,
    )
  }

  @Test
  fun cache_afterAFlush_acceptsChunksAgain() = runBlocking {
    // A flush drains a capped cache, so recording resumes.
    val context = context()
    val manager = AudioCacheManager(context)
    repeat(chunksToFillTheCap + 4) { manager.cacheInput(oneMebibyte()) }
    val unused = manager.flush(flushUserAudio = true, flushModelAudio = false)

    manager.cacheInput(pcm(1, 2, 3))
    val second = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertContentEquals(byteArrayOf(1, 2, 3), artifacts.storedBytes(second, context.session.key))
  }

  @Test
  fun cache_capIsPerCache_soOneSideDoesNotStarveTheOther() = runBlocking {
    // The directions are bounded independently, so a full input cache still records the model.
    val context = context()
    val manager = AudioCacheManager(context)
    repeat(chunksToFillTheCap + 4) { manager.cacheInput(oneMebibyte()) }

    manager.cacheOutput(pcm(9))
    val events = manager.flush(flushUserAudio = true, flushModelAudio = true)

    val modelAudio = events.single { it.content?.role == Role.MODEL }
    val userAudio = events.single { it.content?.role == Role.USER }
    assertEquals(
      chunksToFillTheCap * 1024 * 1024,
      artifacts.storedBytes(userAudio, context.session.key)?.size,
      "the input side was not bounded",
    )
    assertContentEquals(byteArrayOf(9), artifacts.storedBytes(modelAudio, context.session.key))
  }

  @Test
  fun cache_nonAudioBlob_isNotCached() = runBlocking {
    // Downstream treats a recording as audio, so video or an image would become a corrupt file.
    val manager = AudioCacheManager(context())
    manager.cacheInput(Blob(mimeType = "video/mp4", data = byteArrayOf(1)))
    manager.cacheInput(Blob(mimeType = "image/jpeg", data = byteArrayOf(2)))
    manager.cacheOutput(Blob(mimeType = "video/mp4", data = byteArrayOf(3)))

    assertEquals(emptyList(), manager.flush(flushUserAudio = true, flushModelAudio = true))
  }

  @Test
  fun cacheInput_audioWithoutMimeType_isRecordedAsPcm() = runBlocking {
    val manager = AudioCacheManager(context())
    manager.cacheInput(Blob(data = byteArrayOf(7)))

    val events = manager.flush(flushUserAudio = true, flushModelAudio = false)

    assertEquals("audio/pcm", events.single().content?.parts?.single()?.fileData?.mimeType)
  }

  @Test
  fun flush_afterAFailedSave_dropsTheAudio() = runBlocking {
    // A failed save drops the audio instead of keeping it for the next flush, as in Python.
    var failNext = true
    val failingOnce =
      object : ArtifactService by artifacts {
        override suspend fun saveArtifact(
          sessionKey: SessionKey,
          filename: String,
          artifact: Part,
        ): Int {
          if (failNext) {
            failNext = false
            throw RuntimeException("save failed")
          }
          return artifacts.saveArtifact(sessionKey, filename, artifact)
        }
      }
    val context =
      testInvocationContext(agent = DummyAgent(name = "a"), artifactService = failingOnce)
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1, 2))
    val firstFlush = manager.flush(flushUserAudio = false, flushModelAudio = true)
    manager.cacheOutput(pcm(3))
    val secondFlush = manager.flush(flushUserAudio = false, flushModelAudio = true)

    assertEquals(emptyList(), firstFlush)
    assertContentEquals(
      byteArrayOf(3),
      artifacts.storedBytes(secondFlush.single(), context.session.key),
      "the audio from the failed save should have been dropped, not written on the next flush",
    )
  }

  @Test
  fun flush_cancelledDuringSave_rethrowsTheCancellation(): Unit = runBlocking {
    // A failed save swallows its error, but must let a cancellation of the run itself through.
    val cancelling =
      object : ArtifactService by artifacts {
        override suspend fun saveArtifact(
          sessionKey: SessionKey,
          filename: String,
          artifact: Part,
        ): Int = throw CancellationException("cancelled")
      }
    val context =
      testInvocationContext(agent = DummyAgent(name = "a"), artifactService = cancelling)
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1))

    assertFailsWith<CancellationException> {
      manager.flush(flushUserAudio = false, flushModelAudio = true)
    }
  }

  @Test
  fun flush_capHoldsAcrossALiveTransfer() = runBlocking {
    // The cap and byte count live on the context's cache, so they hold across a live transfer.
    val parent = context()
    val parentManager = AudioCacheManager(parent)
    repeat(6) { parentManager.cacheOutput(oneMebibyte()) }
    val childManager = AudioCacheManager(parent.forLiveChild(DummyAgent(name = "child")))
    repeat(6) { childManager.cacheOutput(oneMebibyte()) }

    val event = childManager.flush(flushUserAudio = false, flushModelAudio = true).single()

    assertEquals(
      8 * 1024 * 1024,
      artifacts.storedBytes(event, parent.session.key)?.size,
      "the 8 MiB cap must hold for one side's recording across a transfer",
    )
  }
}
