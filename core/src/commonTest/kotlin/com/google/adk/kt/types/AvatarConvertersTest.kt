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

import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.types.AvatarConfig as GenAiAvatarConfig
import com.google.genai.kotlin.types.Blob as GenAiBlob
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.CustomizedAvatar as GenAiCustomizedAvatar
import com.google.genai.kotlin.types.Part as GenAiPart
import kotlin.test.Test
import kotlinx.serialization.json.Json

/** Tests the avatar config mirror, its converters, and how avatar video comes back. */
class AvatarConvertersTest {

  @Test
  fun toGenaiSdk_prebuiltAvatar_carriesNameAndBitrates() {
    val config =
      AvatarConfig(avatarName = "avatar-1", audioBitrateBps = 64_000, videoBitrateBps = 2_000_000)

    val sdk = config.toGenaiSdk()

    assertThat(sdk.avatarName).isEqualTo("avatar-1")
    assertThat(sdk.audioBitrateBps).isEqualTo(64_000)
    assertThat(sdk.videoBitrateBps).isEqualTo(2_000_000)
    assertThat(sdk.customizedAvatar).isNull()
  }

  @Test
  fun toGenaiSdk_customizedAvatar_carriesTheReferenceImage() {
    val image = byteArrayOf(1, 2, 3)
    val config =
      AvatarConfig(
        customizedAvatar = CustomizedAvatar(imageMimeType = "image/jpeg", imageData = image)
      )

    val sdk = config.toGenaiSdk()

    assertThat(sdk.customizedAvatar?.imageMimeType).isEqualTo("image/jpeg")
    assertThat(sdk.customizedAvatar?.imageData).isEqualTo(image)
  }

  @Test
  fun fromGenaiSdk_everyField_roundTripsUnchanged() {
    val sdk =
      GenAiAvatarConfig(
        avatarName = "avatar-2",
        customizedAvatar =
          GenAiCustomizedAvatar(imageMimeType = "image/png", imageData = byteArrayOf(9, 8)),
        audioBitrateBps = 32_000,
        videoBitrateBps = 1_000_000,
      )

    val roundTripped = sdk.fromGenaiSdk().toGenaiSdk()

    assertThat(roundTripped.avatarName).isEqualTo("avatar-2")
    assertThat(roundTripped.customizedAvatar?.imageMimeType).isEqualTo("image/png")
    assertThat(roundTripped.customizedAvatar?.imageData).isEqualTo(byteArrayOf(9, 8))
    assertThat(roundTripped.audioBitrateBps).isEqualTo(32_000)
    assertThat(roundTripped.videoBitrateBps).isEqualTo(1_000_000)
  }

  @Test
  fun customizedAvatar_equality_comparesImageContentNotIdentity() {
    val one = CustomizedAvatar(imageMimeType = "image/jpeg", imageData = byteArrayOf(1, 2))
    val other = CustomizedAvatar(imageMimeType = "image/jpeg", imageData = byteArrayOf(1, 2))

    assertThat(one).isEqualTo(other)
    assertThat(one.hashCode()).isEqualTo(other.hashCode())
    assertThat(one).isNotEqualTo(one.copy(imageMimeType = "image/png"))
    assertThat(one).isNotEqualTo(one.copy(imageData = byteArrayOf(1, 3)))
  }

  @Test
  fun customizedAvatar_json_encodesTheImageAsBase64() {
    val json =
      Json.encodeToString(
        CustomizedAvatar.serializer(),
        CustomizedAvatar(imageData = byteArrayOf(1, 2, 3)),
      )

    assertThat(json).contains("\"imageData\":\"AQID\"")
  }

  @Test
  fun responseModalities_video_survivesBothConversions() {
    // An avatar session asks for video, so the modality has to make the round trip.
    assertThat(Modality.VIDEO.toGenaiSdk().toKt()).isEqualTo(Modality.VIDEO)
  }

  @Test
  fun fromGenaiSdk_modelTurnWithVideo_keepsTheInlineVideoUntouched() {
    // Avatar video arrives as ordinary inline data; nothing filters by mime type.
    val video = byteArrayOf(4, 5, 6)
    val turn =
      GenAiContent(
        role = "model",
        parts = listOf(GenAiPart(inlineData = GenAiBlob(mimeType = "video/mp4", data = video))),
      )

    val converted = turn.fromGenaiSdk()

    assertThat(converted.parts.single().inlineData?.mimeType).isEqualTo("video/mp4")
    assertThat(converted.parts.single().inlineData?.data).isEqualTo(video)
  }
}
