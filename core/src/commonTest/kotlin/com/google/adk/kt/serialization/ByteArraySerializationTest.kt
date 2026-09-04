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

package com.google.adk.kt.serialization

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Covers reading `ByteArray` fields written in the JSON number-array form this library used to
 * produce, which [LenientByteArraySerializer] still accepts.
 *
 * Separate from `EventSerializationTest`, which covers the event graph's shape: these are about one
 * field encoding and the compatibility owed to sessions already on disk.
 */
@OptIn(FrameworkInternalApi::class)
class ByteArraySerializationTest {

  @Test
  fun blobData_writtenAsTheOldNumberArray_stillDecodes() {
    // The shape an older build persisted. A session is one JSON document, so failing to read this
    // would fail the whole session load rather than just this field.
    val legacy = """{"mimeType":"audio/pcm","data":[1,2,3]}"""

    val decoded = adkJson.decodeFromString(Blob.serializer(), legacy)

    assertEquals(Blob(mimeType = "audio/pcm", data = byteArrayOf(1, 2, 3)), decoded)
  }

  @Test
  fun blobData_oldNumberArrayWithSignedValues_decodesToTheSameBytes() {
    // The old encoder wrote Kotlin's signed bytes, so anything above 0x7F was persisted negative.
    // -1 and -128 are 0xFF and 0x80; these are the values a naive re-read gets wrong.
    val legacy = """{"data":[-1,-128,127,0]}"""

    val decoded = adkJson.decodeFromString(Blob.serializer(), legacy)

    assertContentEquals(byteArrayOf(-1, -128, 127, 0), decoded.data)
  }

  @Test
  fun blobData_readAsOldArrayThenReencoded_isWrittenBackAsBase64() {
    // Reading the old shape must not perpetuate it: what goes back to disk is base64.
    val decoded = adkJson.decodeFromString(Blob.serializer(), """{"data":[1,2,3]}""")

    val reencoded = adkJson.encodeToString(Blob.serializer(), decoded)

    assertTrue(reencoded.contains("\"data\":\"AQID\""), reencoded)
  }

  @Test
  fun blobData_emptyOldNumberArray_decodesToEmptyBytes() {
    // An empty array and an empty base64 string are both legal and must not be confused with null.
    val decoded = adkJson.decodeFromString(Blob.serializer(), """{"data":[]}""")

    assertContentEquals(byteArrayOf(), decoded.data)
  }

  @Test
  fun partThoughtSignature_writtenAsTheOldNumberArray_stillDecodes() {
    val legacy = """{"text":"hello","thoughtSignature":[1,2,3]}"""

    val decoded = adkJson.decodeFromString(Part.serializer(), legacy)

    assertEquals(Part(text = "hello", thoughtSignature = byteArrayOf(1, 2, 3)), decoded)
  }

  @Test
  fun blobData_base64_isStillRead() {
    // The tolerant path must not have cost us the form we actually write.
    val decoded = adkJson.decodeFromString(Blob.serializer(), """{"data":"AQID"}""")

    assertContentEquals(byteArrayOf(1, 2, 3), decoded.data)
  }
}
