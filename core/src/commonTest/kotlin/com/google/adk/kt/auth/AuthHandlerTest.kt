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
import com.google.adk.kt.testing.signInReplyStateKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/** Expected URLs use `STATE` for the random `state`. */
@OptIn(ExperimentalAuthApi::class, SecretAccess::class)
class AuthHandlerTest {

  @Test
  fun signInConfig_apiKeyScheme_dropsTheConfiguredKey() {
    val config = AuthConfig(API_KEY, rawAuthCredential = AuthCredential.ApiKey(Secret("sk-123")))

    val request = config.signInConfig()

    assertEquals(config.copy(rawAuthCredential = AuthCredential.ApiKey()), request)
  }

  @Test
  fun signInConfig_authUriAlreadyExchanged_keepsItWithoutTheSecret() {
    val exchanged = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL, exchanged)

    val request = config.signInConfig()

    assertEquals(
      config.copy(
        rawAuthCredential = CONFIDENTIAL.copy(clientSecret = null),
        exchangedAuthCredential = exchanged.copy(clientSecret = null),
      ),
      request,
    )
  }

  @Test
  fun signInConfig_authUriAlreadyExchanged_needsNoOAuth2RawCredential() {
    // An existing URL is kept before the raw credential is checked.
    val exchanged = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")

    for (raw in listOf(null, AuthCredential.ApiKey(Secret("sk-123")))) {
      val request = AuthConfig(AUTH_CODE, raw, exchanged).signInConfig()

      assertEquals(exchanged.copy(clientSecret = null), request.exchangedAuthCredential)
    }
  }

  @Test
  fun signInConfig_authUriInBothCredentials_keepsTheExchangedOne() {
    val raw = CONFIDENTIAL.copy(authUri = "https://a/auth?state=raw", state = "raw")
    val exchanged = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")

    val request = AuthConfig(AUTH_CODE, raw, exchanged).signInConfig()

    assertEquals(exchanged.copy(clientSecret = null), request.exchangedAuthCredential)
  }

  @Test
  fun signInConfig_authUriInRawCredential_usesIt() {
    val raw = CONFIDENTIAL.copy(authUri = "https://a/auth?state=s", state = "s")

    val request = AuthConfig(AUTH_CODE, raw).signInConfig()

    assertEquals(raw.copy(clientSecret = null), request.exchangedAuthCredential)
  }

  @Test
  fun signInConfig_codeChallengeMethodOtherThanS256_throws() {
    val raw = AuthCredential.OAuth2(clientId = "cid", codeChallengeMethod = "plain")

    assertFailsWith<IllegalArgumentException> { AuthConfig(AUTH_CODE, raw).signInConfig() }
  }

  @Test
  fun signInConfig_confidentialClient_buildsTheAuthorizationUrl() {
    val request = AuthConfig(AUTH_CODE, CONFIDENTIAL).signInConfig()

    assertAuthorizationUrl(request, "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY")
    val exchanged = request.exchangedAuthCredential.oauth2()
    assertEquals(
      CONFIDENTIAL.copy(clientSecret = null, authUri = exchanged.authUri, state = exchanged.state),
      exchanged,
    )
    assertEquals(CONFIDENTIAL.copy(clientSecret = null), request.rawAuthCredential)
  }

  @Test
  fun signInConfig_emptyAuthUri_buildsANewOne() {
    val configs =
      listOf(
        AuthConfig(AUTH_CODE, CONFIDENTIAL.copy(authUri = "")),
        // The new URL comes from the raw credential, not the exchanged one.
        AuthConfig(AUTH_CODE, CONFIDENTIAL, CONFIDENTIAL.copy(authUri = "", clientId = "other")),
      )

    for (config in configs) {
      assertAuthorizationUrl(config.signInConfig(), "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY")
    }
  }

  @Test
  fun signInConfig_emptyCodeChallengeMethodWithSecret_addsNoPkce() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        codeChallengeMethod = "",
        codeVerifier = Secret("w".repeat(43)),
      )

    val request = AuthConfig(AUTH_CODE, raw).signInConfig()

    assertAuthorizationUrl(
      request,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent",
    )
    assertEquals("", request.exchangedAuthCredential.oauth2().codeChallengeMethod)
  }

  @Test
  fun signInConfig_emptyCodeChallengeMethodWithoutSecret_addsPkce() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        codeChallengeMethod = "",
        codeVerifier = Secret("v".repeat(43)),
      )

    val request = AuthConfig(AUTH_CODE, raw).signInConfig()

    assertAuthorizationUrl(
      request,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent" +
        "&code_challenge=7w_YNF9DSfIdPf_pRjSq646_kPr-2-o9NAl16JGghdM&code_challenge_method=S256",
    )
    assertEquals("S256", request.exchangedAuthCredential.oauth2().codeChallengeMethod)
  }

  @Test
  fun signInConfig_explicitCredentialKey_keepsIt() {
    val configs =
      listOf(
        AuthConfig(AUTH_CODE, CONFIDENTIAL, credentialKey = CredentialKey("my_key")),
        AuthConfig(
          AUTH_CODE,
          CONFIDENTIAL.copy(authUri = "https://a/auth?s"),
          credentialKey = CredentialKey("my_key"),
        ),
      )

    for (config in configs) {
      assertEquals("my_key", config.signInConfig().credentialKey.value)
    }
  }

  @Test
  fun signInConfig_httpScheme_dropsPasswordTokenAndHeaders() {
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

    val request = config.signInConfig()

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
  fun signInConfig_openIdConnectScheme_buildsAnAuthorizationUrl() {
    val raw = CONFIDENTIAL.copy(variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT)

    val request = AuthConfig(OIDC, raw).signInConfig()

    assertAuthorizationUrl(
      request,
      "https://o/auth?response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb" +
        "&scope=openid+email&state=STATE&access_type=offline&prompt=consent",
    )
    assertNull(request.exchangedAuthCredential.oauth2().clientSecret)
  }

  @Test
  fun signInConfig_openIdConnectUrlScheme_throws() {
    // Discovery comes later, so there is no endpoint to build this URL from.
    val config =
      AuthConfig(
        AuthScheme.OpenIdConnectUrl("https://o/.well-known/openid-configuration"),
        CONFIDENTIAL,
      )

    assertFailsWith<IllegalArgumentException> { config.signInConfig() }
  }

  @Test
  fun signInConfig_publicClient_addsPkce() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        redirectUri = "https://app/cb",
        codeVerifier = Secret("v".repeat(43)),
      )

    for (client in listOf(raw, raw.copy(clientSecret = Secret("")))) {
      val request = AuthConfig(AUTH_CODE, client).signInConfig()

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
  fun signInConfig_rawCredentialMissingOrNotOAuth2_throws() {
    for (raw in listOf(null, AuthCredential.ApiKey(Secret("sk-123")))) {
      assertFailsWith<IllegalArgumentException> { AuthConfig(AUTH_CODE, raw).signInConfig() }
    }
  }

  @Test
  fun signInConfig_serviceAccountKey_dropsTheCredential() {
    val config = AuthConfig(AuthScheme.Http(scheme = "bearer"), SERVICE_ACCOUNT)

    val request = config.signInConfig()

    assertEquals(config.copy(rawAuthCredential = null), request)
  }

  @Test
  fun signInConfig_serviceAccountKeyWithDefaultCredential_dropsOnlyTheKey() {
    val raw = SERVICE_ACCOUNT.copy(scopes = listOf("s"), useDefaultCredential = true)

    val request = AuthConfig(AuthScheme.Http(scheme = "bearer"), raw).signInConfig()

    assertEquals(raw.copy(serviceAccountCredential = null), request.rawAuthCredential)
  }

  @Test
  fun signInConfig_withoutClientId_throws() {
    assertFailsWith<IllegalArgumentException> {
      AuthConfig(AUTH_CODE, AuthCredential.OAuth2(clientSecret = Secret("sec"))).signInConfig()
    }
  }

  @Test
  fun storedCredential_credentialUnderBothKeys_prefersTheTempKey() {
    val config = AuthConfig(OIDC)
    val temp = AuthCredential.OAuth2(accessToken = Secret("temp"))
    val state =
      mapOf(
        config.credentialKey.value to AuthCredential.OAuth2(accessToken = Secret("plain")),
        config.signInReplyStateKey to temp,
      )

    val response = config.storedCredential(state)

    assertEquals(temp, response)
  }

  @Test
  fun storedCredential_emptyTempValue_fallsBackToThePlainKey() {
    val config = AuthConfig(API_KEY)
    val state = mapOf(config.signInReplyStateKey to "", config.credentialKey.value to "sk-plain")

    val response = config.storedCredential(state)

    assertEquals(AuthCredential.ApiKey(Secret("sk-plain")), response)
  }

  @Test
  fun storedCredential_httpBasicString_fallsBackToThePlainKey() {
    val basic = AuthCredential.Http.basic(username = "u", password = "p")

    for (scheme in listOf(AuthScheme.Http.basic(), AuthScheme.Http(scheme = "Basic"))) {
      val config = AuthConfig(scheme)
      val tempKey = config.signInReplyStateKey

      assertNull(config.storedCredential(mapOf(tempKey to "bare")))
      assertEquals(
        basic,
        config.storedCredential(mapOf(tempKey to "bare", config.credentialKey.value to basic)),
      )
    }
  }

  @Test
  fun storedCredential_malformedTempMap_fallsBackToThePlainKey() {
    val config = AuthConfig(API_KEY)
    val malformed =
      listOf(
        mapOf("authType" to "oauth2", "oauth2" to "not an object"),
        mapOf("authType" to "apiKey", "apiKey" to Secret("not JSON")),
      )

    for (stored in malformed) {
      val state =
        mapOf(config.signInReplyStateKey to stored, config.credentialKey.value to "sk-plain")
      assertEquals(AuthCredential.ApiKey(Secret("sk-plain")), config.storedCredential(state))
    }
  }

  @Test
  fun storedCredential_map_decodesItWithTheConfiguredClient() {
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL)
    val stored =
      mapOf(
        "authType" to "oauth2",
        "oauth2" to mapOf("clientId" to "other", "accessToken" to "tok"),
      )

    val response = config.storedCredential(mapOf(config.credentialKey.value to stored))

    assertEquals(
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        accessToken = Secret("tok"),
        tokenEndpointAuthMethod = null,
      ),
      response,
    )
  }

  @Test
  fun storedCredential_noConfiguredOAuth2Client_keepsTheStoredOne() {
    val config = AuthConfig(OIDC)
    val stored = AuthCredential.OAuth2(clientId = "app", accessToken = Secret("tok"))

    val response = config.storedCredential(mapOf(config.credentialKey.value to stored))

    assertEquals(stored, response)
  }

  @Test
  fun storedCredential_nothingUsableStored_returnsNull() {
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL)
    val states =
      listOf(
        emptyMap(),
        mapOf(config.signInReplyStateKey to 42, config.credentialKey.value to ""),
        mapOf(
          config.credentialKey.value to mapOf("authType" to "oauth2", "oauth2" to "not an object")
        ),
        mapOf("other" to "tok"),
      )

    for (state in states) assertNull(config.storedCredential(state))
  }

  @Test
  fun storedCredential_publicConfiguredClient_dropsTheStoredSecret() {
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL.copy(clientSecret = null))
    val stored =
      AuthCredential.OAuth2(clientId = "other", clientSecret = Secret("x"), authCode = Secret("c"))

    val response = config.storedCredential(mapOf(config.credentialKey.value to stored))

    assertEquals(stored.copy(clientId = "cid", clientSecret = null), response)
  }

  @Test
  fun storedCredential_storedOAuth2Credential_takesTheClientFromTheConfig() {
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL)
    val stored =
      AuthCredential.OAuth2(clientId = "other", clientSecret = Secret("x"), authCode = Secret("c"))

    val response = config.storedCredential(mapOf(config.credentialKey.value to stored))

    assertEquals(stored.copy(clientId = "cid", clientSecret = Secret("sec")), response)
  }

  @Test
  fun storedCredential_string_buildsTheSchemesCredential() {
    val token = Secret("v")
    val cases =
      listOf(
        API_KEY to AuthCredential.ApiKey(token),
        AuthScheme.Http.bearer() to AuthCredential.Http.bearer("v"),
        AuthScheme.Http(scheme = "digest") to
          AuthCredential.Http("digest", AuthCredential.Http.Credentials(token = token)),
        AUTH_CODE to AuthCredential.OAuth2(accessToken = token),
        OIDC to AuthCredential.OAuth2(accessToken = token),
        AuthScheme.OpenIdConnectUrl("https://o/.well-known/openid-configuration") to
          AuthCredential.OAuth2(accessToken = token),
      )

    for ((scheme, expected) in cases) {
      val config = AuthConfig(scheme)
      val state = mapOf(config.signInReplyStateKey to "v")
      assertEquals(expected, config.storedCredential(state))
    }
  }

  private companion object {
    val API_KEY = AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER)

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

    /** The parameters for [CONFIDENTIAL] with [SCOPES], after the endpoint's own. */
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

    /** Checks the URL against [expected], where `STATE` is the random `state`. */
    fun assertAuthorizationUrl(request: AuthConfig, expected: String) {
      val exchanged = request.exchangedAuthCredential.oauth2()
      val state = exchanged.state.orEmpty()
      assertEquals(30, state.length)
      assertEquals(expected, exchanged.authUri?.replace("state=$state&", "state=STATE&"))
    }
  }
}
