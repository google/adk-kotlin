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

import com.google.adk.kt.serialization.LenientByteArraySerializer
import kotlinx.serialization.Serializable

/** Represents binary data. */
@Serializable
data class Blob(
  val mimeType: String? = null,
  val displayName: String? = null,
  @Serializable(with = LenientByteArraySerializer::class) val data: ByteArray? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Blob) return false

    return mimeType == other.mimeType &&
      displayName == other.displayName &&
      data.contentEquals(other.data)
  }

  override fun hashCode(): Int {
    var result = mimeType?.hashCode() ?: 0
    result = 31 * result + (displayName?.hashCode() ?: 0)
    result = 31 * result + (data?.contentHashCode() ?: 0)
    return result
  }
}
