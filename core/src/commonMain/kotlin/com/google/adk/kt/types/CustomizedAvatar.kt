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
import com.google.genai.kotlin.types.ByteArrayAsBase64Serializer
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Serializable

/**
 * A customized avatar appearance, described by a reference image.
 *
 * @property imageMimeType Mime type of the reference image, for example `image/jpeg`.
 * @property imageData The reference image, 9:16 (portrait) and at least 704x1280.
 */
@Serializable
data class CustomizedAvatar(
  val imageMimeType: String? = null,
  @Serializable(with = ByteArrayAsBase64Serializer::class) val imageData: ByteArray? = null,
) {
  // Hand-written for ByteArray: add new properties here, in hashCode, Builder, toBuilder and tests.
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

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().imageMimeType(imageMimeType).imageData(imageData)

  /**
   * Fluent builder for [CustomizedAvatar], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var imageMimeType: String? = null
    private var imageData: ByteArray? = null

    fun imageMimeType(imageMimeType: String?): Builder = apply {
      this.imageMimeType = imageMimeType
    }

    fun imageData(imageData: ByteArray?): Builder = apply { this.imageData = imageData }

    fun build(): CustomizedAvatar =
      CustomizedAvatar(imageMimeType = imageMimeType, imageData = imageData)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
