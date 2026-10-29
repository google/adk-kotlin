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

import com.google.genai.kotlin.types.ByteArrayAsBase64Serializer
import kotlin.jvm.JvmOverloads
import kotlinx.serialization.Serializable

/**
 * A customized avatar appearance, described by a reference image.
 *
 * @property imageMimeType Mime type of the reference image, for example `image/jpeg`.
 * @property imageData The reference image, at most 5 MB: portrait at least 704x1280, or landscape
 *   at least 1280x704.
 */
@Serializable
data class CustomizedAvatar
@JvmOverloads
constructor(
  val imageMimeType: String? = null,
  @Serializable(with = ByteArrayAsBase64Serializer::class) val imageData: ByteArray? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CustomizedAvatar) return false

    return imageMimeType == other.imageMimeType && imageData.contentEquals(other.imageData)
  }

  override fun hashCode(): Int {
    var result = imageMimeType?.hashCode() ?: 0
    result = 31 * result + (imageData?.contentHashCode() ?: 0)
    return result
  }
}
