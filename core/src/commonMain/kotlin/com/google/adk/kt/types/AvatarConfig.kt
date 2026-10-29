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

import kotlin.jvm.JvmOverloads
import kotlinx.serialization.Serializable

/**
 * The avatar a live session renders its replies as.
 *
 * Give either [avatarName] for a prebuilt avatar or [customizedAvatar] for one built from a
 * reference image, and include [Modality.VIDEO] in the response modalities; some models accept it
 * only on its own. Only the Vertex AI backend accepts an avatar.
 *
 * @property avatarName Id of a prebuilt avatar.
 * @property customizedAvatar A custom appearance, built from a reference image.
 * @property audioBitrateBps Bitrate of the compressed audio output.
 * @property videoBitrateBps Bitrate of the compressed video output.
 */
@Serializable
data class AvatarConfig
@JvmOverloads
constructor(
  val avatarName: String? = null,
  val customizedAvatar: CustomizedAvatar? = null,
  val audioBitrateBps: Int? = null,
  val videoBitrateBps: Int? = null,
)
