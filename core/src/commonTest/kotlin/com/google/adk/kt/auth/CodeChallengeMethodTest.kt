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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class CodeChallengeMethodTest {

  @Test
  fun s256_name_isS256() {
    assertEquals("S256", CodeChallengeMethod.S256.name)
  }

  @Test
  fun s256Challenge_rfc7636AppendixBVerifier_returnsRfcChallenge() {
    val challenge =
      CodeChallengeMethod.S256.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
  }

  @Test
  fun s256Challenge_nonAsciiVerifier_throwsWithoutTheVerifier() {
    val verifier = "verifier-\u00e9"

    val e =
      assertFailsWith<IllegalArgumentException> { CodeChallengeMethod.S256.challenge(verifier) }

    assertFalse(verifier in e.message.orEmpty())
  }

  @Test
  fun named_s256_returnsS256() {
    assertSame(CodeChallengeMethod.S256, CodeChallengeMethod.named("S256"))
  }

  @Test
  fun named_otherMethod_throws() {
    for (name in listOf("plain", "s256", "")) {
      assertFailsWith<IllegalArgumentException> { CodeChallengeMethod.named(name) }
    }
  }

  @Test
  fun newCodeVerifier_called_returns48LettersAndDigits() {
    val verifier = newCodeVerifier()

    assertEquals(48, verifier.length)
    assertTrue(
      verifier.matches(Regex("[A-Za-z0-9]+")),
      "verifier has a character outside [A-Za-z0-9]",
    )
  }

  @Test
  fun newCodeVerifier_twoCalls_returnDifferentVerifiers() {
    assertNotEquals(newCodeVerifier(), newCodeVerifier())
  }
}
