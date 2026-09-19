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

package com.google.adk.kt.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SessionNotFoundExceptionTest {

  @Test
  fun construct_noArguments_usesTheDefaultMessageAndNoCause() {
    val failure = SessionNotFoundException()

    assertEquals("Session not found.", failure.message)
    assertNull(failure.cause)
  }

  @Test
  fun construct_withCause_keepsTheCauseForTheTrace() {
    val storeFailure = IllegalStateException("the store rejected the read")

    val failure = SessionNotFoundException("no session for that key", storeFailure)

    assertEquals("no session for that key", failure.message)
    assertSame(storeFailure, failure.cause)
  }

  @Test
  fun throw_isCaughtByAnIllegalArgumentExceptionHandler() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        throw SessionNotFoundException("no session for that key")
      }

    assertEquals("no session for that key", failure.message)
  }
}
