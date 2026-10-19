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
import com.google.adk.kt.serialization.LenientByteArraySerializer
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Represents binary data. */
@Serializable
data class Blob(
  @JsonNames("mime_type") val mimeType: String? = null,
  @JsonNames("display_name") val displayName: String? = null,
  @Serializable(with = LenientByteArraySerializer::class) val data: ByteArray? = null,
) {
  // Hand-written for ByteArray: add new properties here, in hashCode, Builder, toBuilder and tests.
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

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().mimeType(mimeType).displayName(displayName).data(data)

  /**
   * Fluent builder for [Blob], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var mimeType: String? = null
    private var displayName: String? = null
    private var data: ByteArray? = null

    fun mimeType(mimeType: String?): Builder = apply { this.mimeType = mimeType }

    fun displayName(displayName: String?): Builder = apply { this.displayName = displayName }

    fun data(data: ByteArray?): Builder = apply { this.data = data }

    fun build(): Blob = Blob(mimeType = mimeType, displayName = displayName, data = data)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
