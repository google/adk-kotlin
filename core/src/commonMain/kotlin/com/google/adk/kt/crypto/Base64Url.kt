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

/**
 * URL-safe base64 (RFC 4648 section 5) without `=` padding. Calls to [encode] also compile against
 * `kotlin.io.encoding.Base64`, so once `kotlinCompatVersion` reaches 2.2 this can delegate to
 * `Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)`.
 */
internal expect object Base64Url {
  /** Returns [source] encoded. */
  fun encode(source: ByteArray): String
}
