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

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class PkceTest {

  @Test
  fun generateCodeVerifier_called_returns48LettersAndDigits() {
    val verifier = generateCodeVerifier()

    assertEquals(48, verifier.length)
    assertTrue(
      verifier.matches(Regex("[A-Za-z0-9]+")),
      "verifier has a character outside [A-Za-z0-9]",
    )
  }

  @Test
  fun s256CodeChallenge_nonAsciiVerifier_throws() {
    assertFailsWith<IllegalArgumentException> { s256CodeChallenge("verifier-é") }
  }

  @Test
  fun s256CodeChallenge_rfc7636AppendixBVerifier_returnsRfcChallenge() {
    assertEquals(
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      s256CodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
    )
  }
}
