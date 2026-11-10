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
import com.google.adk.kt.testing.testHttpBearerConfig
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.testToolContext
import kotlin.test.assertEquals
import org.junit.Test

@OptIn(ExperimentalAuthApi::class)
class UserCredentialKeyTest {

  @Test
  fun toString_hidesTheUserIdAndTheCredentialKey() {
    val key =
      UserCredentialKey(
        appName = "app",
        userId = "sentinel-user",
        credentialKey = CredentialKey("sentinel-key"),
      )

    assertEquals(
      "UserCredentialKey(appName=app, userId=<redacted>, credentialKey=<redacted>)",
      key.toString(),
    )
  }

  @Test
  fun userCredentialKey_takesTheAppAndUserFromTheSession() {
    val authConfig = testHttpBearerConfig()
    val context = testToolContext(testInvocationContext(session = testSession("app", "user", "s1")))

    assertEquals(
      UserCredentialKey(appName = "app", userId = "user", credentialKey = authConfig.credentialKey),
      context.userCredentialKey(authConfig),
    )
  }
}
