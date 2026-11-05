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

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.testing.capturingLogs
import com.google.adk.kt.testing.signInReplyStateKey
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAuthApi::class)
class AuthHandlerLoggingTest {

  @Test
  fun storedCredential_malformedValue_warnsWithoutTheKeyOrTheValue() {
    val storedValue = "stored-credential-value"
    val state =
      mapOf(CONFIG.signInReplyStateKey to mapOf("authType" to "oauth2", "oauth2" to storedValue))

    val (credential, records) =
      capturingLogs(LOGGER, Level.WARNING) { CONFIG.storedCredential(state) }

    assertNull(credential)
    assertEquals(1, records.size)
    val record = records.single()
    assertTrue("malformed" in record.message.orEmpty())
    for (text in listOf(CONFIG.credentialKey.value, storedValue)) {
      assertFalse(text in record.message.orEmpty())
      assertFalse(generateSequence(record.thrown) { it.cause }.any { text in it.message.orEmpty() })
    }
  }

  @Test
  fun storedCredential_validValue_doesNotWarn() {
    val values = listOf(mapOf("authType" to "apiKey", "apiKey" to "sk-123"), "sk-123")

    for (value in values) {
      val state = mapOf(CONFIG.signInReplyStateKey to value)

      val (credential, records) =
        capturingLogs(LOGGER, Level.WARNING) { CONFIG.storedCredential(state) }

      assertEquals(AuthCredential.ApiKey(Secret("sk-123")), credential)
      assertTrue(records.isEmpty())
    }
  }

  @Test
  fun storedCredential_emptyOrMissingValue_doesNotWarn() {
    for (state in listOf(emptyMap(), mapOf(CONFIG.signInReplyStateKey to ""))) {
      val (_, records) = capturingLogs(LOGGER, Level.WARNING) { CONFIG.storedCredential(state) }

      assertTrue(records.isEmpty())
    }
  }

  private companion object {
    // Named after the file's private `AuthHandler` object.
    const val LOGGER = "com.google.adk.kt.auth.AuthHandler"

    val CONFIG =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        credentialKey = CredentialKey("calendar-key"),
      )
  }
}
