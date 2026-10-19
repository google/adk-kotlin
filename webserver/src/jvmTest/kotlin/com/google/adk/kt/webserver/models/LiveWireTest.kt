/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.webserver.models

import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LiveWireTest {

  @Test
  fun decodeLiveRequest_audioWithoutRate_keepsItsMimeType() {
    // Passed through as ADK Python does; the live models take a bare `audio/pcm` as 16 kHz.
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun decodeLiveRequest_audioWithRate_keepsSampleRate() {
    val request =
      decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm;rate=24000","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm;rate=24000")
  }

  @Test
  fun decodeLiveRequest_otherAudioType_keepsItsMimeType() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/wav","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/wav")
  }

  @Test
  fun decodeLiveRequest_camelCaseMimeType_isAccepted() {
    val request = decodeLiveRequest("""{"blob":{"mimeType":"audio/pcm","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun decodeLiveRequest_imageBlob_becomesVideoInput() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"image/jpeg","data":"AQID"}}""")

    val video = request.input as RealtimeInput.Video
    assertThat(video.blob.mimeType).isEqualTo("image/jpeg")
  }

  @Test
  fun decodeLiveRequest_pdfBlob_isDropped() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"application/pdf","data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequest_textBlob_isDropped() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"text/plain","data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequest_blobWithoutMimeType_isDropped() {
    val request = decodeLiveRequest("""{"blob":{"data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequest_uppercaseAudioMimeType_isDropped() {
    // MIME prefixes match case-sensitively, as in ADK Python.
    val request = decodeLiveRequest("""{"blob":{"mime_type":"AUDIO/PCM","data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_droppedBlobWithText_stillSendsTheText() {
    // As in ADK Python, dropping the blob does not drop the frame's content.
    val request =
      decodeLiveRequest(
        """{"blob":{"mime_type":"application/pdf","data":"AQID"},"content":{"parts":[{"text":"hi"}]},"state_delta":{"k":"v"}}"""
      )

    val input = request.input as ContentInput
    assertThat(input.content.parts.single().text).isEqualTo("hi")
    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequest_standardBase64_isDecoded() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(1, 2, 3))
  }

  @Test
  fun decodeLiveRequest_urlSafeBase64_isDecoded() {
    // "____" is url-safe for three 0xFF bytes; the standard alphabet spells them "////".
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"____"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(-1, -1, -1))
  }

  @Test
  fun decodeLiveRequest_unparseableBase64_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"!!!!"}}""")
    }
  }

  @Test
  fun decodeLiveRequest_unpaddedBase64_throws() {
    // Core's blob decoding wants padding, which browsers' btoa and Python's b64encode both emit.
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"AQI"}}""")
    }
  }

  @Test
  fun decodeLiveRequest_droppedBlobWithBadBase64_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"blob":{"mime_type":"application/pdf","data":"!!!!"}}""")
    }
  }

  @Test
  fun decodeLiveRequests_numberArrayData_throws() {
    // ADK Python reads blob data as base64 text only; core's number-array form is for old sessions.
    assertThrows(SerializationException::class.java) {
      decodeLiveRequests("""{"blob":{"mime_type":"audio/pcm","data":[1,2,3]}}""")
    }
  }

  @Test
  fun decodeLiveRequest_activityStartWithBlob_prefersActivityStart() {
    val request =
      decodeLiveRequest("""{"activity_start":{},"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    assertThat(request.input).isEqualTo(RealtimeInput.ActivityStart)
  }

  @Test
  fun decodeLiveRequest_activityEnd_becomesActivityEnd() {
    val request = decodeLiveRequest("""{"activity_end":{}}""")

    assertThat(request.input).isEqualTo(RealtimeInput.ActivityEnd)
  }

  @Test
  fun decodeLiveRequest_audioStreamEnd_becomesAudioStreamEnd() {
    val request = decodeLiveRequest("""{"audio_stream_end":true}""")

    assertThat(request.input).isEqualTo(RealtimeInput.AudioStreamEnd)
  }

  @Test
  fun decodeLiveRequest_closeFrame_dropsTheOtherInput() {
    val request =
      decodeLiveRequest("""{"close":true,"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    assertThat(request.close).isTrue()
    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequest_closeFrameWithStateDelta_keepsTheStateDelta() {
    val request = decodeLiveRequest("""{"close":true,"state_delta":{"k":"v"}}""")

    assertThat(request.close).isTrue()
    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequest_contentFrame_isTurnByTurn() {
    val request =
      decodeLiveRequest("""{"content":{"role":"user","parts":[{"text":"hello"}]},"partial":true}""")

    val input = request.input as ContentInput
    assertThat(input.partial).isTrue()
    assertThat(input.content.parts.single().text).isEqualTo("hello")
  }

  @Test
  fun decodeLiveRequests_partialWithBlob_sendsTheBlobThenThePartialContent() {
    // `partial` applies only to the content, which follows the blob as in ADK Python.
    val requests =
      decodeLiveRequests(
        """{"blob":{"mime_type":"audio/pcm","data":"AQID"},"content":{"role":"user","parts":[{"text":"hi"}]},"partial":true,"state_delta":{"k":"v"}}"""
      )

    assertThat(requests.map { it.input?.javaClass })
      .containsExactly(RealtimeInput.Audio::class.java, ContentInput::class.java)
      .inOrder()
    assertThat((requests[1].input as ContentInput).partial).isTrue()
    // Partial content makes no user event, so ADK Python applies the delta first, on its own.
    assertThat(requests[0].stateDelta).containsExactly("k", "v")
    assertThat(requests[1].stateDelta).isEmpty()
  }

  @Test
  fun decodeLiveRequest_partialAlone_sendsNothing() {
    // `partial` needs content, so a frame with only the flag has no input rather than a bad one.
    val request = decodeLiveRequest("""{"partial":true}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequest_partialFunctionResponses_dropsPartial() {
    // ContentInput rejects `partial` on function responses, so the decoder drops it.
    val request =
      decodeLiveRequest(
        """{"content":{"role":"user","parts":[{"function_response":{"name":"f","response":{}}}]},"partial":true}"""
      )

    val input = request.input as ContentInput
    assertThat(input.partial).isFalse()
  }

  @Test
  fun decodeLiveRequest_functionCallContent_throws() {
    // A forged call is refused at the edge, rather than reaching the run.
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest(
        """{"content":{"role":"user","parts":[{"function_call":{"name":"forged"}}]}}"""
      )
    }
  }

  @Test
  fun decodeLiveRequest_functionResponseMixedWithText_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest(
        """{"content":{"role":"user","parts":[{"function_response":{"name":"f","response":{}}},{"text":"hi"}]}}"""
      )
    }
  }

  @Test
  fun decodeLiveRequest_contentWithoutParts_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"content":{"role":"user","parts":[]}}""")
    }
  }

  @Test
  fun decodeLiveRequest_stateDelta_isCarried() {
    val request = decodeLiveRequest("""{"state_delta":{"k":"v"}}""")

    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_stateDeltaWithNullValue_removesTheKey() {
    // Session state cannot hold null, so the key is removed, as Kotlin's rewind does for `None`.
    val request = decodeLiveRequest("""{"state_delta":{"k":null,"j":"v","s":"null"}}""")

    // The string "null" is a value, not a removal.
    assertThat(request.stateDelta).containsExactly("k", State.REMOVED, "j", "v", "s", "null")
  }

  @Test
  fun decodeLiveRequests_nullStateDeltaValueOnClose_removesTheKey() {
    val request = decodeLiveRequest("""{"close":true,"state_delta":{"k":null}}""")

    assertThat(request.close).isTrue()
    assertThat(request.stateDelta).containsExactly("k", State.REMOVED)
  }

  @Test
  fun decodeLiveRequests_nestedNullInStateDelta_isKept() {
    // Only a top-level null names a key to remove; a nested one is data, as ADK Python keeps it.
    val request = decodeLiveRequest("""{"state_delta":{"k":{"n":null}}}""")

    assertThat(request.stateDelta["k"]).isEqualTo(mapOf("n" to null))
  }

  @Test
  fun decodeLiveRequests_nullStateDelta_isAnEmptyDelta() {
    assertThat(decodeLiveRequest("""{"state_delta":null,"audio_stream_end":true}""").stateDelta)
      .isEmpty()
  }

  @Test
  fun decodeLiveRequest_nestedPast200Levels_throws() {
    // Python's parser limit, then one deeper; the brackets in the string don't count.
    assertThat(decodeLiveRequest(nestedFrame(depth = 200)).stateDelta).containsKey("k")
    assertThrows(SerializationException::class.java) { decodeLiveRequest(nestedFrame(depth = 201)) }
    // An escaped quote must not end the string, or the brackets after it would hide.
    val deep = "[".repeat(201) + "]".repeat(201)
    assertThrows(SerializationException::class.java) {
      decodeLiveRequest("""{"state_delta":{"k":["a\"b",$deep]}}""")
    }
  }

  /** A frame whose state delta nests to [depth] levels, counting the frame's own object. */
  private fun nestedFrame(depth: Int): String {
    val arrays = depth - 2
    return """{"state_delta":{"k":${"[".repeat(arrays)}"[{"${"]".repeat(arrays)}}}"""
  }

  @Test
  fun decodeLiveRequest_frameThatIsNotAnObject_throws() {
    assertThrows(SerializationException::class.java) { decodeLiveRequest("[1]") }
  }

  @Test
  fun decodeLiveRequest_malformedJson_throws() {
    assertThrows(SerializationException::class.java) { decodeLiveRequest("not json") }
  }

  @Test
  fun decodeLiveRequest_unknownField_isIgnored() {
    val request = decodeLiveRequest("""{"model_config":{"a":1},"audio_stream_end":true}""")

    assertThat(request.input).isEqualTo(RealtimeInput.AudioStreamEnd)
  }

  @Test
  fun encodeLiveEvent_textEvent_isOneJsonObjectWithTheText() {
    val encoded =
      encodeLiveEvent(
        Event(
          author = "agent",
          content = Content(role = Role.MODEL, parts = listOf(Part(text = "hi"))),
        )
      )

    assertThat(encoded).doesNotContain("\n")
    assertThat(encoded).contains("\"text\":\"hi\"")
  }

  @Test
  fun encodeLiveEvent_audioEvent_encodesInlineDataAsBase64() {
    val encoded =
      encodeLiveEvent(
        Event(
          author = "agent",
          content =
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(
                    inlineData =
                      Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1, 2, 3))
                  )
                ),
            ),
        )
      )

    assertThat(encoded).contains("\"data\":\"AQID\"")
  }

  @Test
  fun decodeLiveRequests_contentWithRealtimeInput_sendsBothInAdkPythonsOrder() {
    val requests =
      decodeLiveRequests(
        """{"activity_start":{},"content":{"role":"user","parts":[{"text":"hi"}]},"state_delta":{"k":"v"}}"""
      )

    assertThat(requests.map { it.input?.javaClass })
      .containsExactly(RealtimeInput.ActivityStart::class.java, ContentInput::class.java)
      .inOrder()
    // The state delta rides on the content once, as ADK Python records it on the user event.
    assertThat(requests[0].stateDelta).isEmpty()
    assertThat(requests[1].stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_stateDeltaWithoutContent_ridesOnTheRealtimeInput() {
    val request =
      decodeLiveRequest(
        """{"blob":{"mime_type":"audio/pcm","data":"AQID"},"state_delta":{"k":"v"}}"""
      )

    assertThat(request.input).isInstanceOf(RealtimeInput.Audio::class.java)
    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  /** The one request a frame with a single input decodes to. */
  private fun decodeLiveRequest(text: String): LiveRequest = decodeLiveRequests(text).single()
}
