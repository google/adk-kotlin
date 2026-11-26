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
import com.google.adk.kt.testing.testToolContext
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalAuthApi::class)
class SessionStateCredentialServiceLoggingTest {

  @Test
  fun loadCredential_storedValueNotACredential_logsOnlyItsType() = runBlocking {
    val config =
      AuthConfig(AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER))
    val context = testToolContext()
    context.updateState(config.credentialKey.value, "sk-secret")

    val (credential, records) =
      capturingLogs(SessionStateCredentialService::class.java.name, Level.WARNING) {
        SessionStateCredentialService().loadCredential(config, context)
      }

    assertNull(credential)
    assertEquals("Ignoring a String stored under a credential key.", records.single().message)
  }
}
