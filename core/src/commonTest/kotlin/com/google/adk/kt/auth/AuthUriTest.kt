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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test

/** Expected URLs use `STATE` for the random `state`. */
@OptIn(ExperimentalAuthApi::class, SecretAccess::class)
class AuthUriTest {

  @Test
  fun withNewAuthUri_clientCredentialsFlow_usesItsTokenUrl() {
    val scheme = AuthScheme.OAuth2.clientCredentials("https://c/token", mapOf("c" to "C"))

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(
      credential,
      "https://c/token?response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb" +
        "&scope=c&state=STATE&access_type=offline&prompt=consent",
    )
  }

  @Test
  fun withNewAuthUri_codeChallengeMethodOtherThanS256_throws() {
    for (raw in listOf(CONFIDENTIAL, CONFIDENTIAL.copy(clientSecret = null))) {
      assertFailsWith<IllegalArgumentException> {
        raw.copy(codeChallengeMethod = "plain").withNewAuthUri(AUTH_CODE)
      }
    }
  }

  @Test
  fun withNewAuthUri_codeVerifierWithoutMethod_addsNoChallenge() {
    val raw = CONFIDENTIAL.copy(codeVerifier = Secret("w".repeat(43)))

    val credential = raw.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(credential, "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY")
    assertEquals(raw.copy(authUri = credential.authUri, state = credential.state), credential)
  }

  @Test
  fun withNewAuthUri_confidentialClient_addsAllParameters() {
    val credential = CONFIDENTIAL.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(credential, "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY")
    assertEquals(
      CONFIDENTIAL.copy(authUri = credential.authUri, state = credential.state),
      credential,
    )
  }

  @Test
  fun withNewAuthUri_emptyCodeChallengeMethod_addsNoChallenge() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        codeChallengeMethod = "",
        codeVerifier = Secret("w".repeat(43)),
      )

    val credential = raw.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent",
    )
    assertEquals(raw.copy(authUri = credential.authUri, state = credential.state), credential)
  }

  @Test
  fun withNewAuthUri_emptyPrompt_sendsConsent() {
    val credential = CONFIDENTIAL.copy(prompt = "").withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(credential, "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY")
  }

  @Test
  fun withNewAuthUri_emptyScopeName_omitsScope() {
    val scheme =
      AuthScheme.OAuth2.authorizationCode(
        "https://a/auth?tenant=t",
        "https://a/token",
        mapOf("" to ""),
      )

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&response_type=code&client_id=cid" +
        "&redirect_uri=https%3A%2F%2Fapp%2Fcb&state=STATE&access_type=offline&prompt=consent",
    )
  }

  @Test
  fun withNewAuthUri_endpointQuery_keepsItAsWritten() {
    val cases =
      listOf(
        "https://a/auth?" to "https://a/auth?$CONFIDENTIAL_QUERY",
        "https://a/auth?tenant=t&" to "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY",
        "https://a/auth?t=a%20b" to "https://a/auth?t=a%20b&$CONFIDENTIAL_QUERY",
        "https://a/auth?flag" to "https://a/auth?flag&$CONFIDENTIAL_QUERY",
        "https://a/auth?a=1&&b=2" to "https://a/auth?a=1&&b=2&$CONFIDENTIAL_QUERY",
        "https://a/auth?x=/&y=%7e" to "https://a/auth?x=/&y=%7e&$CONFIDENTIAL_QUERY",
        "https://a/auth?a=1&b=2&a=3" to "https://a/auth?a=1&b=2&a=3&$CONFIDENTIAL_QUERY",
        "https://a/auth?=v&b=2" to "https://a/auth?=v&b=2&$CONFIDENTIAL_QUERY",
      )

    for ((endpoint, expected) in cases) {
      val scheme = AuthScheme.OAuth2.authorizationCode(endpoint, "https://a/token", SCOPES)
      assertAuthorizationUrl(CONFIDENTIAL.withNewAuthUri(scheme), expected)
    }
  }

  @Test
  fun withNewAuthUri_endpointQueryNameInOtherCase_keepsTheParameterNames() {
    val scheme =
      AuthScheme.OAuth2.authorizationCode("https://a/auth?Scope=x", "https://a/token", SCOPES)

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(credential, "https://a/auth?Scope=x&$CONFIDENTIAL_QUERY")
  }

  @Test
  fun withNewAuthUri_endpointWithoutQuery_keepsItAsWritten() {
    for (endpoint in listOf("http://a/auth", "https://a:443/auth", "https://a/x|y/auth")) {
      val scheme = AuthScheme.OAuth2.authorizationCode(endpoint, "https://a/token", SCOPES)

      val credential = CONFIDENTIAL.withNewAuthUri(scheme)

      assertAuthorizationUrl(credential, "$endpoint?$CONFIDENTIAL_QUERY")
    }
  }

  @Test
  fun withNewAuthUri_endpointWithSurroundingWhitespace_trimsIt() {
    val scheme = AuthScheme.OAuth2.authorizationCode(" https://a/auth\n", "https://a/token", SCOPES)

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(credential, "https://a/auth?$CONFIDENTIAL_QUERY")
  }

  @Test
  fun withNewAuthUri_endpointWithFragment_keepsItAfterTheQuery() {
    val cases =
      listOf(
        "https://a/auth#frag" to "https://a/auth?$CONFIDENTIAL_QUERY#frag",
        "https://a/auth?t=1#frag" to "https://a/auth?t=1&$CONFIDENTIAL_QUERY#frag",
        "https://a/auth#x?y" to "https://a/auth?$CONFIDENTIAL_QUERY#x?y",
      )

    for ((endpoint, expected) in cases) {
      val scheme = AuthScheme.OAuth2.authorizationCode(endpoint, "https://a/token", SCOPES)
      assertAuthorizationUrl(CONFIDENTIAL.withNewAuthUri(scheme), expected)
    }
  }

  @Test
  fun withNewAuthUri_explicitS256WithCodeVerifier_addsItsChallenge() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        codeChallengeMethod = "S256",
        codeVerifier = Secret("w".repeat(43)),
      )

    val credential = raw.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent" +
        "&code_challenge=kxR_lMiyp4tyeni4Ddrqn5DNPwlbiu5liDPc5hTIciI&code_challenge_method=S256",
    )
    assertEquals("w".repeat(43), credential.codeVerifier?.reveal())
    assertEquals("S256", credential.codeChallengeMethod)
  }

  @Test
  fun withNewAuthUri_firstFlowWithoutUrl_takesTheNextUrlButTheFirstFlowsScopes() {
    val scheme =
      AuthScheme.OAuth2(
        AuthScheme.OAuth2.Flows(
          implicit = AuthScheme.OAuth2.Flow.Implicit("", scopes = mapOf("i" to "I")),
          authorizationCode = AUTH_CODE.flows.authorizationCode,
        )
      )

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&response_type=code&client_id=cid" +
        "&redirect_uri=https%3A%2F%2Fapp%2Fcb&scope=i&state=STATE&access_type=offline" +
        "&prompt=consent",
    )
  }

  @Test
  fun withNewAuthUri_flowsWithoutEndpoint_throws() {
    val scheme = AuthScheme.OAuth2(AuthScheme.OAuth2.Flows())

    assertFailsWith<IllegalArgumentException> { CONFIDENTIAL.withNewAuthUri(scheme) }
  }

  @Test
  fun withNewAuthUri_implicitAndAuthorizationCodeFlows_usesImplicit() {
    val scheme =
      AuthScheme.OAuth2(
        AuthScheme.OAuth2.Flows(
          implicit = AuthScheme.OAuth2.Flow.Implicit("https://i/auth", scopes = mapOf("i" to "I")),
          authorizationCode = AUTH_CODE.flows.authorizationCode,
        )
      )

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(
      credential,
      "https://i/auth?response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb" +
        "&scope=i&state=STATE&access_type=offline&prompt=consent",
    )
  }

  @Test
  fun withNewAuthUri_notAnAbsoluteHttpUrl_throwsWithoutTheEndpoint() {
    val endpoints =
      listOf(
        "/auth",
        "//a/auth",
        "mailto:a@b",
        "file://h/auth",
        "https://",
        "https:/auth",
        "https:auth",
      )
    for (endpoint in endpoints) {
      val scheme = AuthScheme.OAuth2.authorizationCode(endpoint, "https://a/token", SCOPES)

      val e = assertFailsWith<IllegalArgumentException> { CONFIDENTIAL.withNewAuthUri(scheme) }

      assertFalse(endpoint in e.message.orEmpty())
    }
  }

  @Test
  fun withNewAuthUri_openIdConnectUrlScheme_throws() {
    // Endpoint discovery comes later.
    val scheme = AuthScheme.OpenIdConnectUrl("https://o/.well-known/openid-configuration")

    assertFailsWith<IllegalArgumentException> { OIDC_RAW.withNewAuthUri(scheme) }
  }

  @Test
  fun withNewAuthUri_openIdConnectWithEndpoints_usesItsAuthorizationEndpoint() {
    val credential = OIDC_RAW.withNewAuthUri(OIDC)

    assertAuthorizationUrl(
      credential,
      "https://o/auth?response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb" +
        "&scope=openid+email&state=STATE&access_type=offline&prompt=consent",
    )
    assertEquals(OIDC_RAW.copy(authUri = credential.authUri, state = credential.state), credential)
  }

  @Test
  fun withNewAuthUri_passwordFlowOnly_usesItsTokenUrl() {
    val scheme =
      AuthScheme.OAuth2(
        AuthScheme.OAuth2.Flows(
          password = AuthScheme.OAuth2.Flow.Password("https://p/token", scopes = mapOf("p" to "P"))
        )
      )

    val credential = CONFIDENTIAL.withNewAuthUri(scheme)

    assertAuthorizationUrl(
      credential,
      "https://p/token?response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb" +
        "&scope=p&state=STATE&access_type=offline&prompt=consent",
    )
  }

  @Test
  fun withNewAuthUri_promptAudienceAndNonce_sendsThem() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "cid",
        clientSecret = Secret("sec"),
        prompt = "select_account",
        audience = "aud",
        nonce = "n-1",
      )

    val credential = raw.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&response_type=code&client_id=cid&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=select_account&audience=aud&nonce=n-1",
    )
  }

  @Test
  fun withNewAuthUri_publicClient_addsS256Challenge() {
    val publicClients =
      listOf(
        CONFIDENTIAL.copy(clientSecret = null),
        CONFIDENTIAL.copy(clientSecret = Secret(""), codeChallengeMethod = ""),
      )
    for (raw in publicClients) {
      val credential = raw.withNewAuthUri(AUTH_CODE)

      val verifier = credential.codeVerifier?.reveal().orEmpty()
      assertEquals(48, verifier.length)
      val challenge = CodeChallengeMethod.S256.challenge(verifier)
      assertAuthorizationUrl(
        credential,
        "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY" +
          "&code_challenge=$challenge&code_challenge_method=S256",
      )
      assertEquals("S256", credential.codeChallengeMethod)
    }
  }

  @Test
  fun withNewAuthUri_publicClientWithCodeVerifier_addsItsChallenge() {
    val raw = CONFIDENTIAL.copy(clientSecret = null, codeVerifier = Secret("w".repeat(43)))

    val credential = raw.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY" +
        "&code_challenge=kxR_lMiyp4tyeni4Ddrqn5DNPwlbiu5liDPc5hTIciI&code_challenge_method=S256",
    )
    assertEquals("w".repeat(43), credential.codeVerifier?.reveal())
  }

  @Test
  fun withNewAuthUri_nonAsciiCodeVerifier_throwsWithoutTheVerifier() {
    val verifier = "verifier-\u00e9"
    val raw = CONFIDENTIAL.copy(codeChallengeMethod = "S256", codeVerifier = Secret(verifier))

    val e = assertFailsWith<IllegalArgumentException> { raw.withNewAuthUri(AUTH_CODE) }

    assertFalse(verifier in e.message.orEmpty())
  }

  @Test
  fun withNewAuthUri_reservedAndNonAsciiCharacters_percentEncodesThem() {
    val raw =
      AuthCredential.OAuth2(
        clientId = "c id",
        clientSecret = Secret("sec"),
        redirectUri = "https://app/cb?x=1&y=2",
        nonce = "a b&c=d+\u00e9~_.-*",
      )

    val credential = raw.withNewAuthUri(AUTH_CODE)

    assertAuthorizationUrl(
      credential,
      "https://a/auth?tenant=t&response_type=code&client_id=c+id" +
        "&redirect_uri=https%3A%2F%2Fapp%2Fcb%3Fx%3D1%26y%3D2&scope=read+write&state=STATE" +
        "&access_type=offline&prompt=consent&nonce=a+b%26c%3Dd%2B%C3%A9~_.-%2A",
    )
  }

  @Test
  fun withNewAuthUri_s256WithoutCodeVerifier_generatesOne() {
    val s256 = CONFIDENTIAL.copy(codeChallengeMethod = "S256")
    for (raw in listOf(s256, s256.copy(codeVerifier = Secret("")))) {
      val first = raw.withNewAuthUri(AUTH_CODE)
      val second = raw.withNewAuthUri(AUTH_CODE)

      val verifier = first.codeVerifier?.reveal().orEmpty()
      assertEquals(48, verifier.length)
      val challenge = CodeChallengeMethod.S256.challenge(verifier)
      assertAuthorizationUrl(
        first,
        "https://a/auth?tenant=t&$CONFIDENTIAL_QUERY" +
          "&code_challenge=$challenge&code_challenge_method=S256",
      )
      assertEquals("S256", first.codeChallengeMethod)
      assertNotEquals(verifier, second.codeVerifier?.reveal())
      assertNotEquals(first.state, second.state)
    }
  }

  @Test
  fun withNewAuthUri_unparsableEndpoint_throwsWithoutTheEndpoint() {
    val endpoint = "https://a:port/auth"
    val scheme = AuthScheme.OAuth2.authorizationCode(endpoint, "https://a/token", SCOPES)

    val e = assertFailsWith<IllegalArgumentException> { CONFIDENTIAL.withNewAuthUri(scheme) }

    assertFalse(endpoint in e.message.orEmpty())
    assertNull(e.cause)
  }

  @Test
  fun withNewAuthUri_withoutClientId_throws() {
    for (raw in listOf(CONFIDENTIAL.copy(clientId = null), CONFIDENTIAL.copy(clientId = ""))) {
      assertFailsWith<IllegalArgumentException> { raw.withNewAuthUri(AUTH_CODE) }
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

    /** The parameters for [CONFIDENTIAL] with [SCOPES], after the endpoint's own. */
    const val CONFIDENTIAL_QUERY =
      "response_type=code&client_id=cid&redirect_uri=https%3A%2F%2Fapp%2Fcb&scope=read+write" +
        "&state=STATE&access_type=offline&prompt=consent"

    val OIDC_RAW = CONFIDENTIAL.copy(variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT)

    /** Checks the URL against [expected], where `STATE` stands for the random `state`. */
    fun assertAuthorizationUrl(credential: AuthCredential.OAuth2, expected: String) {
      val state = credential.state.orEmpty()
      assertEquals(30, state.length)
      assertEquals(expected, credential.authUri?.replace("state=$state&", "state=STATE&"))
    }
  }
}
