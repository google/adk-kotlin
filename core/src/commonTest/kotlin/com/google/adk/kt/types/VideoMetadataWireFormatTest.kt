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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Pins the emitted JSON for [VideoMetadata]'s two offsets, and their tolerance for the legacy form.
 *
 * A duration is `"1.5s"` on this API, but kotlinx wrote ISO-8601 (`"PT1.5S"`) before these fields
 * named a serializer. Encoding is asserted on the wire form rather than through a round trip, which
 * passes with both sides wrong together; decoding must still accept the ISO form, because data
 * written by an older build holds it.
 */
@OptIn(FrameworkInternalApi::class)
class VideoMetadataWireFormatTest {

  private val json = Json

  @Test
  fun videoMetadata_encoded_writesOffsetsAsSecondsStrings() {
    val metadata = VideoMetadata(startOffset = 1.seconds, endOffset = 5.seconds)

    val encoded = json.encodeToString(VideoMetadata.serializer(), metadata)

    assertEquals("""{"startOffset":"1s","endOffset":"5s"}""", encoded)
  }

  @Test
  fun videoMetadata_encodedFractionalOffset_keepsTheFraction() {
    val metadata = VideoMetadata(startOffset = 1500.milliseconds)

    val encoded = json.encodeToString(VideoMetadata.serializer(), metadata)

    assertEquals("""{"startOffset":"1.5s"}""", encoded)
  }

  @Test
  fun videoMetadata_decodedFromSecondsString_readsTheOffsets() {
    val decoded =
      json.decodeFromString(
        VideoMetadata.serializer(),
        """{"startOffset":"1.5s","endOffset":"5s","fps":24.0}""",
      )

    assertEquals(1500.milliseconds, decoded.startOffset)
    assertEquals(5.seconds, decoded.endOffset)
    assertEquals(24.0, decoded.fps)
  }

  @Test
  fun videoMetadata_decodedFromIsoString_readsTheLegacyOffsets() {
    val decoded =
      json.decodeFromString(
        VideoMetadata.serializer(),
        """{"startOffset":"PT1.5S","endOffset":"PT5S"}""",
      )

    assertEquals(1500.milliseconds, decoded.startOffset)
    assertEquals(5.seconds, decoded.endOffset)
  }

  @Test
  fun part_decodedFromLegacyIsoOffsets_stillLoads() {
    val decoded =
      adkJson.decodeFromString(
        Part.serializer(),
        """{"videoMetadata":{"startOffset":"PT1.5S","endOffset":"PT5S"}}""",
      )

    assertEquals(1500.milliseconds, decoded.videoMetadata?.startOffset)
    assertEquals(5.seconds, decoded.videoMetadata?.endOffset)
  }

  @Test
  fun videoMetadata_legacyIsoOffsets_areRewrittenAsSecondsStrings() {
    val decoded = json.decodeFromString(VideoMetadata.serializer(), """{"startOffset":"PT1.5S"}""")

    val reencoded = json.encodeToString(VideoMetadata.serializer(), decoded)

    assertEquals("""{"startOffset":"1.5s"}""", reencoded)
  }

  @Test
  fun videoMetadata_decodedFromNegativeIsoString_readsTheOffset() {
    val decoded = json.decodeFromString(VideoMetadata.serializer(), """{"startOffset":"-PT1.5S"}""")

    assertEquals(-1500.milliseconds, decoded.startOffset)
  }

  @Test
  fun videoMetadata_decodedFromMalformedOffset_failsAsASerializationError() {
    assertFailsWith<SerializationException> {
      json.decodeFromString(VideoMetadata.serializer(), """{"startOffset":"Potato"}""")
    }
  }

  @Test
  fun videoMetadata_decodedFromNumericOffset_failsRatherThanCoercing() {
    assertFailsWith<SerializationException> {
      json.decodeFromString(VideoMetadata.serializer(), """{"startOffset":1.5}""")
    }
  }
}
