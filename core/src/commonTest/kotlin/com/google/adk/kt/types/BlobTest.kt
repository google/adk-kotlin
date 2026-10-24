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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BlobTest {
  @Test
  fun equals_sameData_returnsTrue() {
    val blob1 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))
    val blob2 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))

    assertEquals(blob1, blob2)
  }

  @Test
  fun equals_differentData_returnsFalse() {
    val blob1 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))
    val blob2 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 4))

    assertNotEquals(blob1, blob2)
  }

  @Test
  fun equals_oneDataNull_returnsFalse() {
    val blob1 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))
    val blob2 = Blob(mimeType = "image/png", data = null)

    assertNotEquals(blob1, blob2)
  }

  @Test
  fun equals_bothDataNull_returnsTrue() {
    val blob1 = Blob(mimeType = "image/png", data = null)
    val blob2 = Blob(mimeType = "image/png", data = null)

    assertEquals(blob1, blob2)
  }

  @Test
  fun equals_anyPropertyDiffers_returnsFalse() {
    val base = Blob(mimeType = "image/png", displayName = "frame.png", data = byteArrayOf(1, 2, 3))
    val changed =
      listOf(
        base.copy(mimeType = "image/jpeg"),
        base.copy(displayName = "other.png"),
        base.copy(data = byteArrayOf(1, 2, 4)),
      )

    for (blob in changed) {
      assertNotEquals(base, blob)
    }
  }

  @Test
  fun hashCode_sameData_returnsSameHashCode() {
    val blob1 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))
    val blob2 = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))

    assertEquals(blob1.hashCode(), blob2.hashCode())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val blob = Blob(mimeType = "image/png", displayName = "frame.png", data = byteArrayOf(1, 2, 3))

    assertEquals(blob.copy(), blob.toBuilder().build())
    assertEquals(
      blob.copy(displayName = "other.png"),
      blob.toBuilder().displayName("other.png").build(),
    )
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_unsetProperties_matchConstructorDefaults() {
    assertEquals(Blob(), Blob.builder().build())
  }
}
