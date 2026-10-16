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

import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.SaveHookArtifactService
import com.google.adk.kt.testing.storedBytes
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Role
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Covers the recording that `LiveRunConfig.saveLiveBlob` turns on. */
class AudioCacheManagerTest {

  private val artifacts = InMemoryArtifactService()

  private fun context(agentName: String = "weather_agent") =
    testInvocationContext(agent = DummyAgent(name = agentName), artifactService = artifacts)

  private fun pcm(vararg bytes: Byte) = Blob(mimeType = "audio/pcm;rate=24000", data = bytes)

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
    val context = context()
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1, 2))
    manager.cacheOutput(pcm(3))
    manager.cacheOutput(pcm(4, 5))

    val events = manager.flush(flushUserAudio = false, flushModelAudio = true)

    assertEquals(
      listOf<Byte>(1, 2, 3, 4, 5),
      artifacts.storedBytes(events.single(), context.session.key)?.toList(),
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
  fun flush_withoutAnArtifactService_writesNothingAndEmptiesTheCache() = runBlocking {
    val context = testInvocationContext(agent = DummyAgent(name = "a"))
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1))

    assertEquals(emptyList(), manager.flush(flushUserAudio = false, flushModelAudio = true))
    assertEquals(emptyList(), context.frameworkData.outputRealtimeCache.take())
  }

  @Test
  fun cacheInput_callerReusesItsBuffer_keepsWhatWasSent() = runBlocking {
    // Capture loops such as Android's AudioRecord.read often refill one buffer for every chunk.
    val context = context()
    val manager = AudioCacheManager(context)
    val chunk = pcm(1, 2, 3)
    manager.cacheInput(chunk)
    checkNotNull(chunk.data)[0] = 9

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    assertEquals(listOf<Byte>(1, 2, 3), artifacts.storedBytes(event, context.session.key)?.toList())
  }

  @Test
  fun flush_recordingEvent_carriesTheContextsBranch() = runBlocking {
    val context =
      testInvocationContext(
        agent = DummyAgent(name = "weather_agent"),
        artifactService = artifacts,
        branch = "root.weather_agent",
      )
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1))

    val event = manager.flush(flushUserAudio = false, flushModelAudio = true).single()

    assertEquals("root.weather_agent", event.branch)
  }

  @Test
  fun flush_afterAFlush_doesNotRewriteTheSameAudio() = runBlocking {
    val manager = AudioCacheManager(context())
    manager.cacheOutput(pcm(1))
    val unused = manager.flush(flushUserAudio = false, flushModelAudio = true)

    assertEquals(emptyList(), manager.flush(flushUserAudio = false, flushModelAudio = true))
  }

  /** One mebibyte of audio, so a test can build a long recording in a few chunks. */
  @Suppress("KotlinArray") // Blob takes its bytes as a ByteArray, so the array is required here.
  private fun oneMebibyte(mime: String = "audio/pcm;rate=24000") =
    Blob(mimeType = mime, data = ByteArray(1024 * 1024))

  @Test
  fun cacheInput_longTurn_keepsAllItsAudio() = runBlocking {
    // An open mic streams silence until someone speaks; the turn's recording keeps all of it.
    val context = context()
    val manager = AudioCacheManager(context)
    repeat(12) { manager.cacheInput(oneMebibyte()) }
    repeat(20) { manager.cacheInput(pcm(7, 7, 7, 7)) }

    val event = manager.flush(flushUserAudio = true, flushModelAudio = false).single()

    val saved = artifacts.storedBytes(event, context.session.key)?.toList().orEmpty()
    assertEquals(12 * 1024 * 1024 + 80, saved.size, "audio from the long turn was dropped")
    assertEquals(List(80) { 7.toByte() }, saved.takeLast(80))
  }

  @Test
  fun cacheInputOrOutput_nonAudioBlob_isNotCached() = runBlocking {
    // Downstream treats a recording as audio, so video or an image would become a corrupt file.
    val manager = AudioCacheManager(context())
    manager.cacheInput(Blob(mimeType = "video/mp4", data = byteArrayOf(1)))
    manager.cacheInput(Blob(mimeType = "image/jpeg", data = byteArrayOf(2)))
    manager.cacheOutput(Blob(mimeType = "video/mp4", data = byteArrayOf(3)))

    assertEquals(emptyList(), manager.flush(flushUserAudio = true, flushModelAudio = true))
  }

  @Test
  fun cacheInput_audioWithoutMimeType_isRecordedAsSentToGemini() = runBlocking {
    val manager = AudioCacheManager(context())
    manager.cacheInput(Blob(data = byteArrayOf(7)))

    val events = manager.flush(flushUserAudio = true, flushModelAudio = false)

    // Recorded with the rate it is sent with, so a player knows how to read it.
    val fileData = events.single().content?.parts?.single()?.fileData
    assertEquals("audio/pcm;rate=16000", fileData?.mimeType)
    assertTrue(fileData?.fileUri?.substringBefore('#')?.endsWith(".pcm") == true)
  }

  @Test
  fun cacheInput_audioWithABlankMimeType_isRecordedAsSentToGemini() = runBlocking {
    val manager = AudioCacheManager(context())
    manager.cacheInput(Blob(mimeType = " ", data = byteArrayOf(7)))

    val events = manager.flush(flushUserAudio = true, flushModelAudio = false)

    assertEquals(
      "audio/pcm;rate=16000",
      events.single().content?.parts?.single()?.fileData?.mimeType,
    )
  }

  @Test
  fun cacheInputOrOutput_chunkWithNoBytes_isNotRecorded() = runBlocking {
    val manager = AudioCacheManager(context())
    manager.cacheOutput(Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(0)))
    manager.cacheInput(Blob(mimeType = "audio/pcm;rate=16000"))

    assertEquals(emptyList(), manager.flush(flushUserAudio = true, flushModelAudio = true))
  }

  @Test
  fun flush_afterAFailedSave_dropsTheAudio() = runBlocking {
    // A failed save drops the audio instead of keeping it for the next flush, as in Python.
    val failingOnce =
      SaveHookArtifactService(artifacts) { if (it == 0) throw RuntimeException("save failed") }
    val context =
      testInvocationContext(agent = DummyAgent(name = "a"), artifactService = failingOnce)
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1, 2))
    val firstFlush = manager.flush(flushUserAudio = false, flushModelAudio = true)
    manager.cacheOutput(pcm(3))
    val secondFlush = manager.flush(flushUserAudio = false, flushModelAudio = true)

    assertEquals(emptyList(), firstFlush)
    assertEquals(
      listOf<Byte>(3),
      artifacts.storedBytes(secondFlush.single(), context.session.key)?.toList(),
      "the audio from the failed save should have been dropped, not written on the next flush",
    )
  }

  @Test
  fun flush_cancelledDuringSave_rethrowsTheCancellation(): Unit = runBlocking {
    // A failed save swallows its error, but must let a cancellation of the run itself through.
    val cancelling = SaveHookArtifactService(artifacts) { throw CancellationException("cancelled") }
    val context =
      testInvocationContext(agent = DummyAgent(name = "a"), artifactService = cancelling)
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1))

    assertFailsWith<CancellationException> {
      manager.flush(flushUserAudio = false, flushModelAudio = true)
    }
  }

  @Test
  fun flush_contextMadeForAChild_sharesTheCache() = runBlocking {
    // A context made for a child shares the cache, so one side keeps a single recording.
    val parent = context()
    AudioCacheManager(parent).cacheOutput(pcm(1, 2))
    val childManager = AudioCacheManager(parent.forLiveChild(DummyAgent(name = "child")))
    childManager.cacheOutput(pcm(3))

    val event = childManager.flush(flushUserAudio = false, flushModelAudio = true).single()

    assertEquals(listOf<Byte>(1, 2, 3), artifacts.storedBytes(event, parent.session.key)?.toList())
  }

  @Test
  fun flush_referencesTheSavedRevisionUnderTheLiveDirectory() = runBlocking {
    // The reference pins the revision it saved, so a later same-named save cannot stand in for it.
    val context = context()
    val manager = AudioCacheManager(context)
    manager.cacheOutput(pcm(1))

    val uri =
      manager
        .flush(flushUserAudio = false, flushModelAudio = true)
        .single()
        .content
        ?.parts
        ?.single()
        ?.fileData
        ?.fileUri

    val key = context.session.key
    val filename = artifacts.listArtifactKeys(key).single()
    assertEquals("artifact://${key.appName}/${key.userId}/${key.id}/_adk_live/$filename#0", uri)
  }

  @Test
  fun flush_timestampsTheRecordingFromItsFirstChunk() = runBlocking {
    // The event and the filename both say when the recording began, not when it ended.
    val manager = AudioCacheManager(context())
    manager.cacheOutput(pcm(1))
    delay(20)
    val secondChunkAt = Clock.System.now().toEpochMilliseconds()
    manager.cacheOutput(pcm(2))

    val event = manager.flush(flushUserAudio = false, flushModelAudio = true).single()

    assertTrue(event.timestamp < secondChunkAt, "the event is stamped with a later chunk")
    assertTrue(
      event.content?.parts?.single()?.fileData?.fileUri?.contains("_${event.timestamp}.") == true,
      "the filename and the event disagree on when the recording began",
    )
  }
}
