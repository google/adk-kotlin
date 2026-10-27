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
import com.google.adk.kt.annotations.SecretAccess
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/** Expected URLs are ADK Python's for the same config, with `STATE` for the random `state`. */
@OptIn(ExperimentalAuthApi::class, SecretAccess::class)
class AuthHandlerTest {

  @Test
  fun generateAuthRequest_apiKeyScheme_dropsTheConfiguredKey() {
    val config =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        rawAuthCredential = AuthCredential.ApiKey(Secret("sk-123")),
      )

    val request = AuthHandler(config).generateAuthRequest()

    assertEquals(config.copy(rawAuthCredential = AuthCredential.ApiKey()), request)
  }

  @Test
  fun generateAuthRequest_authUriAlreadyExchanged_keepsItWithoutTheSecret() {
    val exchanged = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL, exchanged)

    val request = AuthHandler(config).generateAuthRequest()

    assertEquals(
      config.copy(
        rawAuthCredential = CONFIDENTIAL.copy(clientSecret = null),
        exchangedAuthCredential = exchanged.copy(clientSecret = null),
      ),
      request,
    )
  }

  @Test
  fun generateAuthRequest_authUriAlreadyExchanged_needsNoOAuth2RawCredential() {
    // As in ADK Python, an existing URL is kept before the raw credential is checked.
    val exchanged = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")

    for (raw in listOf(null, AuthCredential.ApiKey(Secret("sk-123")))) {
      val request = AuthHandler(AuthConfig(AUTH_CODE, raw, exchanged)).generateAuthRequest()

      assertEquals(exchanged.copy(clientSecret = null), request.exchangedAuthCredential)
    }
  }

  @Test
  fun generateAuthRequest_authUriInBothCredentials_keepsTheExchangedOne() {
    val raw = CONFIDENTIAL.copy(authUri = "https://a/auth?state=raw", state = "raw")
    val exchanged = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")

    val request = AuthHandler(AuthConfig(AUTH_CODE, raw, exchanged)).generateAuthRequest()

    assertEquals(exchanged.copy(clientSecret = null), request.exchangedAuthCredential)
  }

  @Test
  fun generateAuthRequest_authUriInRawCredential_usesIt() {
    val raw = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")

    val request = AuthHandler(AuthConfig(AUTH_CODE, raw)).generateAuthRequest()

    assertEquals(raw.copy(clientSecret = null), request.exchangedAuthCredential)
  }

  @Test
  fun generateAuthRequest_codeChallengeMethodOtherThanS256_throws() {
    val raw = AuthCredential.OAuth2(clientId = "cid", codeChallengeMethod = "plain")

    assertFailsWith<IllegalArgumentException> {
      AuthHandler(AuthConfig(AUTH_CODE, raw)).generateAuthRequest()
    }
  }

  @Test
  fun generateAuthRequest_confidentialClient_matchesAdkPython() {
    val request = AuthHandler(AuthConfig(AUTH_CODE, CONFIDENTIAL)).generateAuthRequest()

    assertAuthorizationUrl(request, "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY")
    val exchanged = request.exchangedAuthCredential.oauth2()
    assertEquals(
      CONFIDENTIAL.copy(clientSecret = null, authUri = exchanged.authUri, state = exchanged.state),
      exchanged,
    )
    assertEquals(CONFIDENTIAL.copy(clientSecret = null), request.rawAuthCredential)
  }

  @Test
  fun generateAuthRequest_emptyAuthUri_buildsANewOne() {
    val configs =
      listOf(
        AuthConfig(AUTH_CODE, CONFIDENTIAL.copy(authUri = "")),
        AuthConfig(AUTH_CODE, CONFIDENTIAL, CONFIDENTIAL.copy(authUri = "")),
      )

    for (config in configs) {
      assertAuthorizationUrl(
        AuthHandler(config).generateAuthRequest(),
        "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY",
      )
    }
  }

  @Test
  fun generateAuthRequest_emptyCodeChallengeMethodWithSecret_addsNoPkce() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        codeChallengeMethod = "",
        codeVerifier = Secret("w".repeat(43)),
      )

    val request = AuthHandler(AuthConfig(AUTH_CODE, raw)).generateAuthRequest()

    assertAuthorizationUrl(
      request,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent",
    )
    assertEquals("", request.exchangedAuthCredential.oauth2().codeChallengeMethod)
  }

  @Test
  fun generateAuthRequest_emptyCodeChallengeMethodWithoutSecret_addsPkce() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        codeChallengeMethod = "",
        codeVerifier = Secret("v".repeat(43)),
      )

    val request = AuthHandler(AuthConfig(AUTH_CODE, raw)).generateAuthRequest()

    assertAuthorizationUrl(
      request,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent" +
        "&code_challenge=7w_YNF9DSfIdPf_pRjSq646_kPr-2-o9NAl16JGghdM&code_challenge_method=S256",
    )
    assertEquals("S256", request.exchangedAuthCredential.oauth2().codeChallengeMethod)
  }

  @Test
  fun generateAuthRequest_explicitCredentialKey_keepsIt() {
    val configs =
      listOf(
        AuthConfig(AUTH_CODE, CONFIDENTIAL, credentialKey = "my_key"),
        AuthConfig(
          AUTH_CODE,
          CONFIDENTIAL.copy(authUri = "https://a/auth?s"),
          credentialKey = "my_key",
        ),
      )

    for (config in configs) {
      assertEquals("my_key", AuthHandler(config).generateAuthRequest().credentialKey)
    }
  }

  @Test
  fun generateAuthRequest_httpScheme_dropsPasswordTokenAndHeaders() {
    val config =
      AuthConfig(
        AuthScheme.Http(scheme = "basic"),
        rawAuthCredential =
          AuthCredential.Http(
            scheme = "basic",
            credentials = AuthCredential.Http.Credentials("user", Secret("pw"), Secret("tok")),
            additionalHeaders = mapOf("X-Extra" to Secret("h")),
          ),
      )

    val request = AuthHandler(config).generateAuthRequest()

    assertEquals(
      config.copy(
        rawAuthCredential =
          AuthCredential.Http(
            scheme = "basic",
            credentials = AuthCredential.Http.Credentials("user"),
          )
      ),
      request,
    )
  }

  @Test
  fun generateAuthRequest_openIdConnectScheme_buildsAnAuthorizationUrl() {
    val raw = CONFIDENTIAL.copy(variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT)

    val request = AuthHandler(AuthConfig(OIDC, raw)).generateAuthRequest()

    assertAuthorizationUrl(
      request,
      "https://o/auth?response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb" +
        "&scope=openid+email&state=STATE&access_type=offline&prompt=consent",
    )
    assertNull(request.exchangedAuthCredential.oauth2().clientSecret)
  }

  @Test
  fun generateAuthRequest_openIdConnectUrlScheme_throws() {
    // Discovery comes later; ADK Python can't build this URL either.
    val config =
      AuthConfig(
        AuthScheme.OpenIdConnectUrl("https://o/.well-known/openid-configuration"),
        CONFIDENTIAL,
      )

    assertFailsWith<IllegalArgumentException> { AuthHandler(config).generateAuthRequest() }
  }

  @Test
  fun generateAuthRequest_publicClient_addsPkceAsAdkPythonDoes() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        redirectUri = "https://app/cb",
        codeVerifier = Secret("v".repeat(43)),
      )

    for (client in listOf(raw, raw.copy(clientSecret = Secret("")))) {
      val request = AuthHandler(AuthConfig(AUTH_CODE, client)).generateAuthRequest()

      assertAuthorizationUrl(
        request,
        "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY" +
          "&code_challenge=7w_YNF9DSfIdPf_pRjSq646_kPr-2-o9NAl16JGghdM&code_challenge_method=S256",
      )
      val exchanged = request.exchangedAuthCredential.oauth2()
      assertEquals("v".repeat(43), exchanged.codeVerifier?.reveal())
      assertEquals("S256", exchanged.codeChallengeMethod)
      assertNull(request.rawAuthCredential.oauth2().codeChallengeMethod)
    }
  }

  @Test
  fun generateAuthRequest_rawCredentialMissingOrNotOAuth2_throws() {
    for (raw in listOf(null, AuthCredential.ApiKey(Secret("sk-123")))) {
      assertFailsWith<IllegalArgumentException> {
        AuthHandler(AuthConfig(AUTH_CODE, raw)).generateAuthRequest()
      }
    }
  }

  @Test
  fun generateAuthRequest_serviceAccount_keepsTheKey() {
    // As in ADK Python, a service account key isn't one of the secrets the request drops.
    val config = AuthConfig(AuthScheme.Http(scheme = "bearer"), SERVICE_ACCOUNT)

    val request = AuthHandler(config).generateAuthRequest()

    assertEquals(config, request)
  }

  @Test
  fun generateAuthRequest_withoutClientId_throws() {
    assertFailsWith<IllegalArgumentException> {
      AuthHandler(AuthConfig(AUTH_CODE, AuthCredential.OAuth2(clientSecret = Secret("sec"))))
        .generateAuthRequest()
    }
  }

  private companion object {
    val SCOPES = mapOf("read" to "Read", "write" to "Write")

    val AUTH_CODE =
      AuthScheme.OAuth2.authorizationCode(
        authorizationUrl = "https://a/auth?tenant=t",
        tokenUrl = "https://a/token",
        scopes = SCOPES,
      )

    val OIDC =
      AuthScheme.OpenIdConnect(
        authorizationEndpoint = "https://o/auth",
        tokenEndpoint = "https://o/token",
        scopes = listOf("openid", "email"),
      )

    val CONFIDENTIAL =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        redirectUri = "https://app/cb",
      )

    /** ADK Python's parameters for [CONFIDENTIAL] with [SCOPES], after the endpoint's own. */
    const val CONFIDENTIAL_QUERY =
      "response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb&scope=read+write" +
        "&state=STATE&access_type=offline&prompt=consent"

    val SERVICE_ACCOUNT =
      AuthCredential.ServiceAccount(
        AuthCredential.ServiceAccount.Credential(
          projectId = "p",
          privateKeyId = Secret("kid"),
          privateKey = Secret("pk"),
          clientEmail = "sa@p.iam.gserviceaccount.com",
          clientId = "1",
          authUri = "https://a/auth",
          tokenUri = "https://a/token",
          authProviderX509CertUrl = "https://a/certs",
          clientX509CertUrl = "https://a/cert",
          universeDomain = "googleapis.com",
        )
      )

    fun AuthCredential?.oauth2(): AuthCredential.OAuth2 = this as AuthCredential.OAuth2

    /** Checks the URL against ADK Python's [expected] one, where `STATE` is the random `state`. */
    fun assertAuthorizationUrl(request: AuthConfig, expected: String) {
      val exchanged = request.exchangedAuthCredential.oauth2()
      val state = exchanged.state.orEmpty()
      assertEquals(30, state.length)
      assertEquals(expected, exchanged.authUri?.replace("state=$state&", "state=STATE&"))
    }
  }
}
