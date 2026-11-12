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
import com.google.adk.kt.events.EventActions
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
  fun decodeLiveRequests_audioWithoutRate_keepsItsMimeType() {
    // Passed through unchanged; the live models take a bare `audio/pcm` as 16 kHz.
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun decodeLiveRequests_audioWithRate_keepsSampleRate() {
    val request =
      decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm;rate=24000","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm;rate=24000")
  }

  @Test
  fun decodeLiveRequests_otherAudioType_keepsItsMimeType() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/wav","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/wav")
  }

  @Test
  fun decodeLiveRequests_camelCaseMimeType_isAccepted() {
    val request = decodeLiveRequest("""{"blob":{"mimeType":"audio/pcm","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun decodeLiveRequests_imageBlob_becomesVideoInput() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"image/jpeg","data":"AQID"}}""")

    val video = request.input as RealtimeInput.Video
    assertThat(video.blob.mimeType).isEqualTo("image/jpeg")
  }

  @Test
  fun decodeLiveRequests_pdfBlob_isDropped() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"application/pdf","data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_textBlob_isDropped() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"text/plain","data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_blobWithoutMimeType_isDropped() {
    val request = decodeLiveRequest("""{"blob":{"data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_uppercaseAudioMimeType_isDropped() {
    // MIME prefixes match case-sensitively.
    val request = decodeLiveRequest("""{"blob":{"mime_type":"AUDIO/PCM","data":"AQID"}}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_droppedBlobWithText_stillSendsTheText() {
    // Dropping the blob does not drop the frame's content.
    val request =
      decodeLiveRequest(
        """{"blob":{"mime_type":"application/pdf","data":"AQID"},"content":{"parts":[{"text":"hi"}]},"state_delta":{"k":"v"}}"""
      )

    val input = request.input as ContentInput
    assertThat(input.content.parts.single().text).isEqualTo("hi")
    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_standardBase64_isDecoded() {
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(1, 2, 3))
  }

  @Test
  fun decodeLiveRequests_urlSafeBase64_isDecoded() {
    // "____" is url-safe for three 0xFF bytes; the standard alphabet spells them "////".
    val request = decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"____"}}""")

    val audio = request.input as RealtimeInput.Audio
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(-1, -1, -1))
  }

  @Test
  fun decodeLiveRequests_unparseableBase64_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"!!!!"}}""")
    }
  }

  @Test
  fun decodeLiveRequests_unpaddedBase64_throws() {
    // Core's blob decoding wants padding, which standard encoders such as the browser's btoa emit.
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"blob":{"mime_type":"audio/pcm","data":"AQI"}}""")
    }
  }

  @Test
  fun decodeLiveRequests_droppedBlobWithBadBase64_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"blob":{"mime_type":"application/pdf","data":"!!!!"}}""")
    }
  }

  @Test
  fun decodeLiveRequests_numberArrayData_throws() {
    // The blob takes base64 text only; core's number-array form is for old sessions.
    assertThrows(SerializationException::class.java) {
      decodeLiveRequests("""{"blob":{"mime_type":"audio/pcm","data":[1,2,3]}}""")
    }
  }

  @Test
  fun decodeLiveRequests_everyRealtimeField_prefersActivityStart() {
    val request =
      decodeLiveRequest(
        """{"activity_start":{},"activity_end":{},"audio_stream_end":true,"blob":{"mime_type":"audio/pcm","data":"AQID"}}"""
      )

    assertThat(request.input).isEqualTo(RealtimeInput.ActivityStart)
  }

  @Test
  fun decodeLiveRequests_activityEndWithEndOfAudioAndBlob_prefersActivityEnd() {
    val request =
      decodeLiveRequest(
        """{"activity_end":{},"audio_stream_end":true,"blob":{"mime_type":"audio/pcm","data":"AQID"}}"""
      )

    assertThat(request.input).isEqualTo(RealtimeInput.ActivityEnd)
  }

  @Test
  fun decodeLiveRequests_endOfAudioWithBlob_prefersEndOfAudio() {
    val request =
      decodeLiveRequest(
        """{"audio_stream_end":true,"blob":{"mime_type":"audio/pcm","data":"AQID"}}"""
      )

    assertThat(request.input).isEqualTo(RealtimeInput.AudioStreamEnd)
  }

  @Test
  fun decodeLiveRequests_activityEnd_becomesActivityEnd() {
    val request = decodeLiveRequest("""{"activity_end":{}}""")

    assertThat(request.input).isEqualTo(RealtimeInput.ActivityEnd)
  }

  @Test
  fun decodeLiveRequests_audioStreamEnd_becomesAudioStreamEnd() {
    val request = decodeLiveRequest("""{"audio_stream_end":true}""")

    assertThat(request.input).isEqualTo(RealtimeInput.AudioStreamEnd)
  }

  @Test
  fun decodeLiveRequests_closeFrame_dropsTheOtherInput() {
    val request =
      decodeLiveRequest("""{"close":true,"blob":{"mime_type":"audio/pcm","data":"AQID"}}""")

    assertThat(request.close).isTrue()
    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_closeFrameWithStateDelta_keepsTheStateDelta() {
    val request = decodeLiveRequest("""{"close":true,"state_delta":{"k":"v"}}""")

    assertThat(request.close).isTrue()
    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_contentFrame_isTurnByTurn() {
    val request =
      decodeLiveRequest("""{"content":{"role":"user","parts":[{"text":"hello"}]},"partial":true}""")

    val input = request.input as ContentInput
    assertThat(input.partial).isTrue()
    assertThat(input.content.parts.single().text).isEqualTo("hello")
  }

  @Test
  fun decodeLiveRequests_partialWithBlob_sendsTheBlobThenThePartialContent() {
    // `partial` applies only to the content, which follows the blob.
    val requests =
      decodeLiveRequests(
        """{"blob":{"mime_type":"audio/pcm","data":"AQID"},"content":{"role":"user","parts":[{"text":"hi"}]},"partial":true,"state_delta":{"k":"v"}}"""
      )

    assertThat(requests.map { it.input?.javaClass })
      .containsExactly(RealtimeInput.Audio::class.java, ContentInput::class.java)
      .inOrder()
    assertThat((requests[1].input as ContentInput).partial).isTrue()
    // Partial content makes no user event, so the delta goes first, on its own.
    assertThat(requests[0].stateDelta).containsExactly("k", "v")
    assertThat(requests[1].stateDelta).isEmpty()
  }

  @Test
  fun decodeLiveRequests_partialAlone_sendsNothing() {
    // `partial` needs content, so a frame with only the flag has no input rather than a bad one.
    val request = decodeLiveRequest("""{"partial":true}""")

    assertThat(request.input).isNull()
  }

  @Test
  fun decodeLiveRequests_partialFunctionResponses_dropsPartial() {
    // ContentInput rejects `partial` on function responses, so the decoder drops it.
    val request =
      decodeLiveRequest(
        """{"content":{"role":"user","parts":[{"function_response":{"name":"f","response":{}}}]},"partial":true}"""
      )

    val input = request.input as ContentInput
    assertThat(input.partial).isFalse()
  }

  @Test
  fun decodeLiveRequests_functionCallContent_throws() {
    // A forged call is refused at the edge, rather than reaching the run.
    val error =
      assertThrows(IllegalArgumentException::class.java) {
        decodeLiveRequest(
          """{"content":{"role":"user","parts":[{"function_call":{"name":"forged"}}]}}"""
        )
      }

    // The content check's own error surfaces, not the decoder's generic one.
    assertThat(error).isNotInstanceOf(SerializationException::class.java)
  }

  @Test
  fun decodeLiveRequests_functionResponseMixedWithText_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest(
        """{"content":{"role":"user","parts":[{"function_response":{"name":"f","response":{}}},{"text":"hi"}]}}"""
      )
    }
  }

  @Test
  fun decodeLiveRequests_contentWithoutParts_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      decodeLiveRequest("""{"content":{"role":"user","parts":[]}}""")
    }
  }

  @Test
  fun decodeLiveRequests_stateDelta_isCarried() {
    val request = decodeLiveRequest("""{"state_delta":{"k":"v"}}""")

    assertThat(request.stateDelta).containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_stateDeltaWithNullValue_removesTheKey() {
    // Session state cannot hold null, so the key is removed.
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
    // Only a top-level null names a key to remove; a nested one is data and is kept.
    val request = decodeLiveRequest("""{"state_delta":{"k":{"n":null}}}""")

    assertThat(request.stateDelta["k"]).isEqualTo(mapOf("n" to null))
  }

  @Test
  fun decodeLiveRequests_nullStateDelta_isAnEmptyDelta() {
    assertThat(decodeLiveRequest("""{"state_delta":null,"audio_stream_end":true}""").stateDelta)
      .isEmpty()
  }

  @Test
  fun decodeLiveRequests_nestedTo200Levels_decodes() {
    // The brackets inside the frame's strings don't count toward the depth.
    assertThat(decodeLiveRequest(nestedFrame(depth = 200)).stateDelta).containsKey("k")
  }

  @Test
  fun decodeLiveRequests_nestedPast200Levels_throws() {
    assertThrows(SerializationException::class.java) { decodeLiveRequest(nestedFrame(depth = 201)) }
  }

  @Test
  fun decodeLiveRequests_escapedQuoteInAString_stillCountsTheBracketsAfterIt() {
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
  fun decodeLiveRequests_camelCaseFieldNames_areAccepted() {
    assertThat(decodeLiveRequest("""{"activityStart":{}}""").input)
      .isEqualTo(RealtimeInput.ActivityStart)
    assertThat(decodeLiveRequest("""{"activityEnd":{}}""").input)
      .isEqualTo(RealtimeInput.ActivityEnd)
    assertThat(decodeLiveRequest("""{"audioStreamEnd":true}""").input)
      .isEqualTo(RealtimeInput.AudioStreamEnd)
    assertThat(decodeLiveRequest("""{"stateDelta":{"k":"v"}}""").stateDelta)
      .containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_bothSpellingsOfAFlag_camelCaseWins() {
    assertThat(decodeLiveRequest("""{"audioStreamEnd":false,"audio_stream_end":true}""").input)
      .isNull()
    assertThat(decodeLiveRequest("""{"audio_stream_end":true,"audioStreamEnd":false}""").input)
      .isNull()
  }

  @Test
  fun decodeLiveRequests_bothSpellingsOfStateDelta_camelCaseWins() {
    assertThat(decodeLiveRequest("""{"stateDelta":{"b":"y"},"state_delta":{"a":"x"}}""").stateDelta)
      .containsExactly("b", "y")
    assertThat(decodeLiveRequest("""{"state_delta":{"a":"x"},"stateDelta":{"b":"y"}}""").stateDelta)
      .containsExactly("b", "y")
  }

  @Test
  fun decodeLiveRequests_nullValueInCamelCaseStateDelta_removesTheKey() {
    assertThat(decodeLiveRequest("""{"stateDelta":{"k":null}}""").stateDelta)
      .containsExactly("k", State.REMOVED)
    assertThat(decodeLiveRequest("""{"state_delta":{},"stateDelta":{"k":null}}""").stateDelta)
      .containsExactly("k", State.REMOVED)
  }

  @Test
  fun decodeLiveRequests_emptyCamelCaseStateDelta_winsOverASnakeCaseOne() {
    assertThat(decodeLiveRequest("""{"stateDelta":{},"state_delta":{"k":null}}""").stateDelta)
      .isEmpty()
  }

  @Test
  fun decodeLiveRequests_nullCamelCaseStateDelta_winsOverASnakeCaseOne() {
    assertThat(decodeLiveRequest("""{"stateDelta":null,"state_delta":{"k":null}}""").stateDelta)
      .isEmpty()
  }

  @Test
  fun decodeLiveRequests_nullSnakeCaseStateDelta_keepsTheCamelCaseOne() {
    assertThat(decodeLiveRequest("""{"stateDelta":{"k":"v"},"state_delta":null}""").stateDelta)
      .containsExactly("k", "v")
    assertThat(decodeLiveRequest("""{"state_delta":null,"stateDelta":{"k":"v"}}""").stateDelta)
      .containsExactly("k", "v")
  }

  @Test
  fun decodeLiveRequests_frameThatIsNotAnObject_throws() {
    assertThrows(SerializationException::class.java) { decodeLiveRequest("[1]") }
  }

  @Test
  fun decodeLiveRequests_malformedJson_throws() {
    val error =
      assertThrows(SerializationException::class.java) { decodeLiveRequest("not json: secret") }

    assertThat(error).hasMessageThat().doesNotContain("secret")
    assertThat(error.cause).isNull()
  }

  @Test
  fun decodeLiveRequests_mistypedField_throwsWithoutTheFrame() {
    // A frame is user content, so neither the message nor a cause may carry it.
    val error =
      assertThrows(SerializationException::class.java) {
        decodeLiveRequests("""{"audio_stream_end":"secret"}""")
      }

    assertThat(error).hasMessageThat().doesNotContain("secret")
    assertThat(error.cause).isNull()
  }

  @Test
  fun decodeLiveRequests_badBase64_throwsWithoutTheFrame() {
    val error =
      assertThrows(SerializationException::class.java) {
        decodeLiveRequests("""{"blob":{"mime_type":"audio/pcm","data":"!secret!"}}""")
      }

    assertThat(error).hasMessageThat().doesNotContain("secret")
    assertThat(error.cause).isNull()
  }

  @Test
  fun decodeLiveRequests_unknownField_isIgnored() {
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
  fun encodeLiveEvent_credentialInStateDelta_leavesOutItsSecret() {
    val credential = mapOf("authType" to "apiKey", "apiKey" to "secret-sentinel")
    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf("user:credential" to credential)),
      )

    val encoded = encodeLiveEvent(event)

    assertThat(encoded).contains("\"authType\":\"apiKey\"")
    assertThat(encoded).doesNotContain("secret-sentinel")
  }

  @Test
  fun decodeLiveRequests_contentWithRealtimeInput_sendsTheRealtimeInputFirst() {
    val requests =
      decodeLiveRequests(
        """{"activity_start":{},"content":{"role":"user","parts":[{"text":"hi"}]},"state_delta":{"k":"v"}}"""
      )

    assertThat(requests.map { it.input?.javaClass })
      .containsExactly(RealtimeInput.ActivityStart::class.java, ContentInput::class.java)
      .inOrder()
    // The state delta rides once, on the content, which records it on the user event.
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

  @Test
  fun decodeLiveRequests_functionResponseWithBlob_sendsTheDeltaWithTheBlob() {
    // A function response makes no user event, so the delta goes first, on its own.
    val requests =
      decodeLiveRequests(
        """{"blob":{"mime_type":"audio/pcm","data":"AQID"},"content":{"role":"user","parts":[{"function_response":{"name":"f","response":{}}}]},"state_delta":{"k":"v"}}"""
      )

    assertThat(requests[0].stateDelta).containsExactly("k", "v")
    assertThat(requests[1].stateDelta).isEmpty()
  }

  @Test
  fun decodeLiveRequests_numberArrayInContentInlineData_throws() {
    assertThrows(SerializationException::class.java) {
      decodeLiveRequests(
        """{"content":{"role":"user","parts":[{"inline_data":{"mime_type":"image/png","data":[1,2,3]}}]}}"""
      )
    }
    assertThrows(SerializationException::class.java) {
      decodeLiveRequests(
        """{"content":{"role":"user","parts":[{"inlineData":{"mimeType":"image/png","data":[1,2,3]}}]}}"""
      )
    }
  }

  /** The one request a frame with a single input decodes to. */
  private fun decodeLiveRequest(text: String): LiveRequest = decodeLiveRequests(text).single()
}
