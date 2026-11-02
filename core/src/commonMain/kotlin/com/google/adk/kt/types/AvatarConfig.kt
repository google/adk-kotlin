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
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Serializable

/**
 * The avatar a live session renders its replies as.
 *
 * Give either [avatarName] for a prebuilt avatar or [customizedAvatar] for one built from a
 * reference image, and include [Modality.VIDEO] in the response modalities, alone for some models.
 *
 * @property avatarName Id of a prebuilt avatar.
 * @property customizedAvatar A custom appearance, built from a reference image.
 * @property audioBitrateBps Bitrate of the compressed audio output.
 * @property videoBitrateBps Bitrate of the compressed video output.
 */
@Serializable
data class AvatarConfig(
  val avatarName: String? = null,
  val customizedAvatar: CustomizedAvatar? = null,
  val audioBitrateBps: Int? = null,
  val videoBitrateBps: Int? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .avatarName(avatarName)
      .customizedAvatar(customizedAvatar)
      .audioBitrateBps(audioBitrateBps)
      .videoBitrateBps(videoBitrateBps)

  /**
   * Fluent builder for [AvatarConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var avatarName: String? = null
    private var customizedAvatar: CustomizedAvatar? = null
    private var audioBitrateBps: Int? = null
    private var videoBitrateBps: Int? = null

    fun avatarName(avatarName: String?): Builder = apply { this.avatarName = avatarName }

    fun customizedAvatar(customizedAvatar: CustomizedAvatar?): Builder = apply {
      this.customizedAvatar = customizedAvatar
    }

    fun audioBitrateBps(audioBitrateBps: Int?): Builder = apply {
      this.audioBitrateBps = audioBitrateBps
    }

    fun videoBitrateBps(videoBitrateBps: Int?): Builder = apply {
      this.videoBitrateBps = videoBitrateBps
    }

    fun build(): AvatarConfig =
      AvatarConfig(
        avatarName = avatarName,
        customizedAvatar = customizedAvatar,
        audioBitrateBps = audioBitrateBps,
        videoBitrateBps = videoBitrateBps,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
