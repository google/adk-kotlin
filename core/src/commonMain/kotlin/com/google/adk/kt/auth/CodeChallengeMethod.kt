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

package com.google.adk.kt.auth

import com.google.adk.kt.crypto.generateRandomString
import com.google.adk.kt.crypto.sha256Base64Url

/**
 * How a PKCE code challenge is derived from its code verifier (RFC 7636 `code_challenge_method`). A
 * method's [name] is the `code_challenge_method` value sent with the authorization request.
 */
internal enum class CodeChallengeMethod {
  /**
   * The `S256` method: the URL-safe, unpadded base64 of the verifier's SHA-256 digest (RFC 7636
   * section 4.2).
   */
  S256;

  /**
   * Returns the code challenge for [codeVerifier].
   *
   * @throws IllegalArgumentException if [codeVerifier] is not ASCII. The rest of the RFC 7636
   *   grammar (43 to 128 characters from `[A-Za-z0-9-._~]`) is not checked.
   */
  fun challenge(codeVerifier: String): String =
    when (this) {
      S256 -> {
        require(codeVerifier.all { it.code < 0x80 }) { "PKCE code verifier must be ASCII." }
        sha256Base64Url(codeVerifier)
      }
    }

  companion object {
    /**
     * Returns the method called [name].
     *
     * @throws IllegalArgumentException if it isn't supported; only `S256` is.
     */
    fun named(name: String): CodeChallengeMethod =
      requireNotNull(entries.find { it.name == name }) {
        "Only the S256 code challenge method is supported."
      }
  }
}

/** Returns a new random code verifier of 48 letters and digits; RFC 7636 allows 43 to 128. */
internal fun newCodeVerifier(): String = generateRandomString(48)
