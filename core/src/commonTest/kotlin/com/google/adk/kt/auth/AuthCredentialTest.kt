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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalAuthApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, AdkJavaInteropApi::class)
class AuthCredentialTest {

  @Test
  fun toString_apiKey_redactsKey() {
    assertEquals("ApiKey(apiKey=<redacted>)", API_KEY.toString())
  }

  @Test
  fun toString_http_redactsSecretFields() {
    assertEquals(
      "Http(scheme=basic, " +
        "credentials=Credentials(username=user, password=<redacted>, token=<redacted>), " +
        "additionalHeaders={X-Key=<redacted>})",
      HTTP.toString(),
    )
  }

  @Test
  fun toString_httpCredentials_redactsPasswordAndToken() {
    assertEquals(
      "Credentials(username=user, password=<redacted>, token=<redacted>)",
      HTTP_CREDENTIALS.toString(),
    )
  }

  @Test
  fun toString_oauth2Unset_printsNullForUnsetFields() {
    assertEquals(
      "OAuth2(variant=OAUTH2, clientId=null, clientSecret=null, authUri=null, nonce=null, " +
        "state=null, redirectUri=null, authResponseUri=null, authCode=null, accessToken=null, " +
        "refreshToken=null, idToken=null, expiresAt=null, expiresIn=null, audience=null, " +
        "prompt=null, codeVerifier=null, codeChallengeMethod=null, " +
        "tokenEndpointAuthMethod=CLIENT_SECRET_BASIC)",
      AuthCredential.OAuth2().toString(),
    )
  }

  @Test
  fun toString_oauth2_redactsSecretsAndSignInValues() {
    assertEquals(
      "OAuth2(variant=OPEN_ID_CONNECT, clientId=client-id, clientSecret=<redacted>, " +
        "authUri=<redacted>, nonce=<redacted>, state=<redacted>, redirectUri=<redacted>, " +
        "authResponseUri=<redacted>, authCode=<redacted>, accessToken=<redacted>, " +
        "refreshToken=<redacted>, idToken=<redacted>, expiresAt=2026-01-01T00:00:00Z, " +
        "expiresIn=1h, audience=audience, prompt=select_account, codeVerifier=<redacted>, " +
        "codeChallengeMethod=S256, tokenEndpointAuthMethod=PRIVATE_KEY_JWT)",
      OAUTH2.toString(),
    )
  }

  @Test
  fun toBuilder_apiKey_matchesCopy() {
    assertEquals(API_KEY.copy(), API_KEY.toBuilder().build())
  }

  @Test
  fun toBuilder_http_matchesCopy() {
    assertEquals(HTTP.copy(), HTTP.toBuilder().build())
  }

  @Test
  fun toBuilder_httpCredentials_matchesCopy() {
    assertEquals(HTTP_CREDENTIALS.copy(), HTTP_CREDENTIALS.toBuilder().build())
  }

  @Test
  fun toBuilder_oauth2_matchesCopy() {
    assertEquals(OAUTH2.copy(), OAUTH2.toBuilder().build())
  }

  @Test
  fun constructor_oauth2Unset_usesOAuth2AndClientSecretBasic() {
    val credential = AuthCredential.OAuth2()

    assertEquals(AuthCredential.OAuth2.Variant.OAUTH2, credential.variant)
    assertEquals(
      AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
      credential.tokenEndpointAuthMethod,
    )
  }

  @Test
  fun builder_apiKeyNothingSet_matchesConstructorDefaults() {
    assertEquals(AuthCredential.ApiKey(), AuthCredential.ApiKey.builder().build())
  }

  @Test
  fun builder_httpOnlyRequiredFieldsSet_matchesConstructorDefaults() {
    assertEquals(
      AuthCredential.Http(scheme = "bearer", credentials = AuthCredential.Http.Credentials()),
      AuthCredential.Http.builder()
        .scheme("bearer")
        .credentials(AuthCredential.Http.Credentials())
        .build(),
    )
  }

  @Test
  fun builder_httpCredentialsNothingSet_matchesConstructorDefaults() {
    assertEquals(
      AuthCredential.Http.Credentials(),
      AuthCredential.Http.Credentials.builder().build(),
    )
  }

  @Test
  fun builder_oauth2NothingSet_matchesConstructorDefaults() {
    assertEquals(AuthCredential.OAuth2(), AuthCredential.OAuth2.builder().build())
  }

  @Test
  fun toBuilder_oauth2SubSecondTimes_keepsPrecision() {
    val credential =
      AuthCredential.OAuth2(
        expiresAt = Instant.fromEpochSeconds(1767225600, 500_000_000),
        expiresIn = 1500.milliseconds,
      )

    assertEquals(credential, credential.toBuilder().build())
  }

  @Test
  fun expiresInSeconds_oauth2_returnsWholeSecondsOrNull() {
    assertEquals(1L, AuthCredential.OAuth2(expiresIn = 1500.milliseconds).expiresInSeconds())
    assertNull(AuthCredential.OAuth2().expiresInSeconds())
  }

  @Test
  fun builder_oauth2SecondsSetters_convertToInstantAndDuration() {
    assertEquals(
      AuthCredential.OAuth2(
        expiresAt = Instant.fromEpochSeconds(1767225600),
        expiresIn = 3600.seconds,
      ),
      AuthCredential.OAuth2.builder()
        .expiresAtEpochSeconds(1767225600)
        .expiresInSeconds(3600)
        .build(),
    )
  }

  @Test
  fun builder_oauth2NullableExpiresInSeconds_setsOrClears() {
    val seconds: Long? = 3600

    assertEquals(
      AuthCredential.OAuth2(expiresIn = 3600.seconds),
      AuthCredential.OAuth2.builder().expiresInSeconds(seconds).build(),
    )
    assertEquals(
      AuthCredential.OAuth2(),
      AuthCredential.OAuth2.builder().expiresIn(1.seconds).expiresInSeconds(null).build(),
    )
  }

  @Test
  fun builder_oauth2TokenEndpointAuthMethodNull_keepsNull() {
    assertEquals(
      AuthCredential.OAuth2(tokenEndpointAuthMethod = null),
      AuthCredential.OAuth2.builder().tokenEndpointAuthMethod(null).build(),
    )
  }

  @Test
  fun builder_apiKeySecretSetter_wrapsValueInSecret() {
    assertEquals(Secret("key"), AuthCredential.ApiKey.builder().apiKey("key").build().apiKey)
  }

  @Test
  fun builder_httpCredentialsSecretSetters_wrapValuesInSecret() {
    assertEquals(
      AuthCredential.Http.Credentials(password = Secret("password"), token = Secret("token")),
      AuthCredential.Http.Credentials.builder().password("password").token("token").build(),
    )
  }

  @Test
  fun builder_httpAdditionalHeaders_wrapsValuesInSecret() {
    assertEquals(
      mapOf("X-Key" to Secret("value")),
      AuthCredential.Http.builder()
        .scheme("basic")
        .credentials(AuthCredential.Http.Credentials())
        .additionalHeaders(mapOf("X-Key" to "value"))
        .build()
        .additionalHeaders,
    )
  }

  @Test
  fun builder_oauth2SecretSetters_wrapValuesInSecret() {
    assertEquals(
      AuthCredential.OAuth2(
        clientSecret = Secret("client-secret"),
        authResponseUri = Secret("auth-response-uri"),
        authCode = Secret("auth-code"),
        accessToken = Secret("access-token"),
        refreshToken = Secret("refresh-token"),
        idToken = Secret("id-token"),
        codeVerifier = Secret("code-verifier"),
      ),
      AuthCredential.OAuth2.builder()
        .clientSecret("client-secret")
        .authResponseUri("auth-response-uri")
        .authCode("auth-code")
        .accessToken("access-token")
        .refreshToken("refresh-token")
        .idToken("id-token")
        .codeVerifier("code-verifier")
        .build(),
    )
  }

  @Test
  fun builder_httpWithoutScheme_throws() {
    assertFailsWith<IllegalStateException> {
      AuthCredential.Http.builder().credentials(AuthCredential.Http.Credentials()).build()
    }
  }

  @Test
  fun builder_httpWithoutCredentials_throws() {
    assertFailsWith<IllegalStateException> { AuthCredential.Http.builder().scheme("basic").build() }
  }

  @Test
  fun bearer_token_buildsBearerCredential() {
    assertEquals(
      AuthCredential.Http(
        scheme = "bearer",
        credentials = AuthCredential.Http.Credentials(token = Secret("token")),
      ),
      AuthCredential.Http.bearer("token"),
    )
  }

  @Test
  fun basic_usernameAndPassword_buildsBasicCredential() {
    assertEquals(
      AuthCredential.Http(
        scheme = "basic",
        credentials =
          AuthCredential.Http.Credentials(username = "user", password = Secret("password")),
      ),
      AuthCredential.Http.basic(username = "user", password = "password"),
    )
  }

  @Test
  fun of_apiKey_buildsApiKeyCredential() {
    assertEquals(
      AuthCredential.ApiKey(apiKey = Secret("sk-123")),
      AuthCredential.ApiKey.of("sk-123"),
    )
  }

  @Test
  fun accessToken_tokenAndExpiry_setsOnlyThose() {
    val expiresAt = Instant.fromEpochSeconds(1_700_000_000)

    assertEquals(
      AuthCredential.OAuth2(accessToken = Secret("at"), expiresAt = expiresAt),
      AuthCredential.OAuth2.accessToken("at", expiresAt),
    )
    assertEquals(
      AuthCredential.OAuth2(accessToken = Secret("at")),
      AuthCredential.OAuth2.accessToken("at"),
    )
  }

  @Test
  fun toString_serviceAccount_redactsPrivateKey() {
    assertEquals(
      "ServiceAccount(serviceAccountCredential=Credential(type=service_account, " +
        "projectId=project, privateKeyId=<redacted>, privateKey=<redacted>, " +
        "clientEmail=agent@project.iam.gserviceaccount.com, " +
        "clientId=1234, authUri=https://accounts.google.com/o/oauth2/auth, " +
        "tokenUri=https://oauth2.googleapis.com/token, " +
        "authProviderX509CertUrl=https://www.googleapis.com/oauth2/v1/certs, " +
        "clientX509CertUrl=https://www.googleapis.com/robot/v1/metadata/x509/agent, " +
        "universeDomain=googleapis.com), scopes=[scope], useDefaultCredential=true, " +
        "useIdToken=true, audience=https://service.example)",
      SERVICE_ACCOUNT.toString(),
    )
  }

  @Test
  fun toBuilder_serviceAccount_matchesCopy() {
    assertEquals(SERVICE_ACCOUNT.copy(), SERVICE_ACCOUNT.toBuilder().build())
  }

  @Test
  fun toBuilder_serviceAccountCredential_matchesCopy() {
    assertEquals(SERVICE_ACCOUNT_CREDENTIAL.copy(), SERVICE_ACCOUNT_CREDENTIAL.toBuilder().build())
  }

  @Test
  fun toBuilder_serviceAccountDefaultCredential_matchesCopy() {
    val credential = AuthCredential.ServiceAccount.applicationDefault(listOf("scope"))

    assertEquals(credential, credential.toBuilder().build())
  }

  @Test
  fun constructor_serviceAccountKeyOnly_keepsKey() {
    val credential =
      AuthCredential.ServiceAccount(serviceAccountCredential = SERVICE_ACCOUNT_CREDENTIAL)

    assertEquals(SERVICE_ACCOUNT_CREDENTIAL, credential.serviceAccountCredential)
    assertFalse(credential.useDefaultCredential)
  }

  @Test
  fun constructor_serviceAccountWithoutKeyOrDefaultCredential_throws() {
    assertFailsWith<IllegalArgumentException> { AuthCredential.ServiceAccount() }
  }

  @Test
  fun constructor_serviceAccountIdTokenWithoutAudience_throws() {
    assertFailsWith<IllegalArgumentException> {
      AuthCredential.ServiceAccount(useDefaultCredential = true, useIdToken = true)
    }
    assertFailsWith<IllegalArgumentException> {
      AuthCredential.ServiceAccount(useDefaultCredential = true, useIdToken = true, audience = "")
    }
  }

  @Test
  fun builder_serviceAccountRequiredFieldsOnly_matchesConstructorDefaults() {
    assertEquals(
      AuthCredential.ServiceAccount(useDefaultCredential = true),
      AuthCredential.ServiceAccount.builder().useDefaultCredential(true).build(),
    )
  }

  @Test
  fun builder_serviceAccountCredentialRequiredFieldsOnly_matchesConstructorDefaults() {
    assertEquals(
      AuthCredential.ServiceAccount.Credential(
        projectId = "project",
        privateKeyId = Secret("key-id"),
        privateKey = Secret("key"),
        clientEmail = "email",
        clientId = "id",
        authUri = "auth",
        tokenUri = "token",
        authProviderX509CertUrl = "provider-certs",
        clientX509CertUrl = "client-cert",
        universeDomain = "googleapis.com",
      ),
      AuthCredential.ServiceAccount.Credential.builder()
        .projectId("project")
        .privateKeyId("key-id")
        .privateKey("key")
        .clientEmail("email")
        .clientId("id")
        .authUri("auth")
        .tokenUri("token")
        .authProviderX509CertUrl("provider-certs")
        .clientX509CertUrl("client-cert")
        .universeDomain("googleapis.com")
        .build(),
    )
  }

  @Test
  fun builder_serviceAccountUnset_throwsFromConstructorRule() {
    assertFailsWith<IllegalArgumentException> { AuthCredential.ServiceAccount.builder().build() }
  }

  @Test
  fun builder_serviceAccountCredentialWithoutPrivateKey_throws() {
    assertFailsWith<IllegalStateException> {
      AuthCredential.ServiceAccount.Credential.builder()
        .projectId("project")
        .privateKeyId("key-id")
        .clientEmail("email")
        .clientId("id")
        .authUri("auth")
        .tokenUri("token")
        .authProviderX509CertUrl("provider-certs")
        .clientX509CertUrl("client-cert")
        .universeDomain("googleapis.com")
        .build()
    }
  }

  @Test
  fun applicationDefault_scopes_usesDefaultCredential() {
    assertEquals(
      AuthCredential.ServiceAccount(useDefaultCredential = true, scopes = listOf("scope")),
      AuthCredential.ServiceAccount.applicationDefault(listOf("scope")),
    )
  }

  @Test
  fun fromKey_key_usesKey() {
    assertEquals(
      AuthCredential.ServiceAccount(
        serviceAccountCredential = SERVICE_ACCOUNT_CREDENTIAL,
        scopes = listOf("scope"),
      ),
      AuthCredential.ServiceAccount.fromKey(SERVICE_ACCOUNT_CREDENTIAL, listOf("scope")),
    )
  }

  @Test
  fun forIdToken_noKey_usesDefaultCredential() {
    assertEquals(
      AuthCredential.ServiceAccount(
        useDefaultCredential = true,
        useIdToken = true,
        audience = "https://service.example",
      ),
      AuthCredential.ServiceAccount.forIdToken("https://service.example"),
    )
  }

  @Test
  fun forIdToken_key_usesKey() {
    assertEquals(
      AuthCredential.ServiceAccount(
        serviceAccountCredential = SERVICE_ACCOUNT_CREDENTIAL,
        useIdToken = true,
        audience = "https://service.example",
      ),
      AuthCredential.ServiceAccount.forIdToken(
        "https://service.example",
        SERVICE_ACCOUNT_CREDENTIAL,
      ),
    )
  }

  @Test
  fun forIdToken_emptyAudience_throws() {
    assertFailsWith<IllegalArgumentException> { AuthCredential.ServiceAccount.forIdToken("") }
  }

  private companion object {
    val API_KEY = AuthCredential.ApiKey(apiKey = Secret("sentinel-api-key"))

    val HTTP_CREDENTIALS =
      AuthCredential.Http.Credentials(
        username = "user",
        password = Secret("sentinel-password"),
        token = Secret("sentinel-token"),
      )

    val HTTP =
      AuthCredential.Http(
        scheme = "basic",
        credentials = HTTP_CREDENTIALS,
        additionalHeaders = mapOf("X-Key" to Secret("sentinel-header")),
      )

    val OAUTH2 =
      AuthCredential.OAuth2(
        variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
        clientId = "client-id",
        clientSecret = Secret("sentinel-client-secret"),
        authUri = "https://auth.example/authorize?state=sentinel-state&nonce=sentinel-nonce",
        nonce = "sentinel-nonce",
        state = "sentinel-state",
        redirectUri = "https://app.example/callback?tenant=sentinel-tenant",
        authResponseUri = Secret("sentinel-auth-response-uri"),
        authCode = Secret("sentinel-auth-code"),
        accessToken = Secret("sentinel-access-token"),
        refreshToken = Secret("sentinel-refresh-token"),
        idToken = Secret("sentinel-id-token"),
        expiresAt = Instant.fromEpochSeconds(1767225600),
        expiresIn = 1.hours,
        audience = "audience",
        prompt = "select_account",
        codeVerifier = Secret("sentinel-code-verifier"),
        codeChallengeMethod = "S256",
        tokenEndpointAuthMethod = AuthCredential.OAuth2.TokenEndpointAuthMethod.PRIVATE_KEY_JWT,
      )

    val SERVICE_ACCOUNT_CREDENTIAL =
      AuthCredential.ServiceAccount.Credential(
        type = "service_account",
        projectId = "project",
        privateKeyId = Secret("sentinel-private-key-id"),
        privateKey = Secret("sentinel-private-key"),
        clientEmail = "agent@project.iam.gserviceaccount.com",
        clientId = "1234",
        authUri = "https://accounts.google.com/o/oauth2/auth",
        tokenUri = "https://oauth2.googleapis.com/token",
        authProviderX509CertUrl = "https://www.googleapis.com/oauth2/v1/certs",
        clientX509CertUrl = "https://www.googleapis.com/robot/v1/metadata/x509/agent",
        universeDomain = "googleapis.com",
      )

    val SERVICE_ACCOUNT =
      AuthCredential.ServiceAccount(
        serviceAccountCredential = SERVICE_ACCOUNT_CREDENTIAL,
        scopes = listOf("scope"),
        useDefaultCredential = true,
        useIdToken = true,
        audience = "https://service.example",
      )
  }
}
