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

import com.google.adk.kt.crypto.secureRandom

private val LETTERS_AND_DIGITS: List<Char> = ('A'..'Z') + ('a'..'z') + ('0'..'9')

/**
 * Returns [length] letters and digits from a secure random generator. PKCE extends OAuth2, so its
 * code verifier comes from here too, and nothing in this file depends on PKCE.
 */
internal fun generateRandomString(length: Int): String {
  val random = secureRandom()
  return CharArray(length) { LETTERS_AND_DIGITS.random(random) }.concatToString()
}

/**
 * Returns a new random OAuth2 `state` value of 30 letters and digits, the length ADK Python
 * generates.
 */
internal fun generateOAuth2State(): String = generateRandomString(30)
