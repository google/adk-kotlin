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

package com.google.adk.kt.crypto

import java.security.MessageDigest
import java.util.Base64

internal actual fun sha256Hex(input: String): String =
  sha256(input).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal actual fun sha256Base64Url(input: String): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(input))

private fun sha256(input: String): ByteArray =
  MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
