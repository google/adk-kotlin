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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test

@OptIn(ExperimentalAuthApi::class)
class OAuth2Test {

  @Test
  fun withoutClientSecret_oauth2Credential_dropsOnlyTheSecret() {
    assertEquals(STORED.copy(clientSecret = null), STORED.withoutClientSecret())
  }

  @Test
  fun withoutClientSecret_otherCredential_keepsIt() {
    assertSame(API_KEY, API_KEY.withoutClientSecret())
  }

  @Test
  fun withConfiguredClient_bothOAuth2_takesTheConfiguredClient() {
    assertEquals(
      STORED.copy(clientId = "cid", clientSecret = Secret("sec")),
      STORED.withConfiguredClient(CONFIGURED),
    )
  }

  @Test
  fun withConfiguredClient_publicConfiguredClient_dropsTheStoredSecret() {
    val credential = STORED.withConfiguredClient(CONFIGURED.copy(clientSecret = null))

    assertNull((credential as AuthCredential.OAuth2).clientSecret)
  }

  @Test
  fun withConfiguredClient_notBothOAuth2_keepsTheCredential() {
    assertSame(STORED, STORED.withConfiguredClient(null))
    assertSame(STORED, STORED.withConfiguredClient(API_KEY))
    assertSame(API_KEY, API_KEY.withConfiguredClient(CONFIGURED))
  }

  @Test
  fun withConfiguredClientSecret_bothOAuth2_takesOnlyTheSecret() {
    assertEquals(
      STORED.copy(clientSecret = Secret("sec")),
      STORED.withConfiguredClientSecret(CONFIGURED),
    )
  }

  @Test
  fun withConfiguredClientSecret_publicConfiguredClient_dropsTheStoredSecret() {
    assertEquals(
      STORED.copy(clientSecret = null),
      STORED.withConfiguredClientSecret(CONFIGURED.copy(clientSecret = null)),
    )
  }

  @Test
  fun withConfiguredClientSecret_notBothOAuth2_keepsTheCredential() {
    assertSame(STORED, STORED.withConfiguredClientSecret(null))
    assertSame(STORED, STORED.withConfiguredClientSecret(API_KEY))
    assertSame(API_KEY, API_KEY.withConfiguredClientSecret(CONFIGURED))
  }

  private companion object {
    val STORED =
      AuthCredential.OAuth2(
        clientId = "app",
        clientSecret = Secret("from-app"),
        accessToken = Secret("at"),
      )

    val CONFIGURED = AuthCredential.OAuth2(clientId = "cid", clientSecret = Secret("sec"))

    val API_KEY = AuthCredential.ApiKey(Secret("k"))
  }
}
