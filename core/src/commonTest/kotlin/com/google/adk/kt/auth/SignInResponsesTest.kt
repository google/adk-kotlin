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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.signInResponseStateKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class SignInResponsesTest {

  @Test
  fun storeSignInResponse_apiKeyResponseToOAuth2Request_storesItAsIs() {
    // A credential of another type takes none of the request's OAuth2 fields.
    val state = State()

    val outcome =
      SIGN_IN_REQUEST.storeSignInResponse(
        mapOf("authType" to "apiKey", "apiKey" to "user-key"),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      AuthCredential.ApiKey(Secret("user-key")),
      state[SIGN_IN_REQUEST.signInResponseStateKey],
    )
  }

  @Test
  fun storeSignInResponse_apiKeyResponseWithoutAKey_storesNothing() {
    // As output writes a typed credential, which a client could post back.
    val config = AuthConfig(API_KEY, credentialKey = CredentialKey("api-key"))
    val state = State()

    val outcome = config.storeSignInResponse(mapOf("authType" to "apiKey"), state)

    assertEquals(StoreSignInResponseOutcome.NO_CREDENTIAL, outcome)
    assertTrue(state.isEmpty())
  }

  @Test
  fun storeSignInResponse_apiKeyResponse_storesTheKey() {
    val config = AuthConfig(API_KEY, credentialKey = CredentialKey("api-key"))
    val state = State()

    val outcome =
      config.storeSignInResponse(mapOf("authType" to "apiKey", "apiKey" to "user-key"), state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(json("""{"authType": "apiKey", "apiKey": "user-key"}"""), state.storedJson(config))
  }

  @Test
  fun storeSignInResponse_authConfigResponse_storesItsExchangedCredential() {
    // The response's own scheme doesn't count; the request's key does.
    val state = State()

    val outcome =
      SIGN_IN_REQUEST.storeSignInResponse(
        mapOf("authScheme" to HTTP_BEARER, "exchangedAuthCredential" to TOKENS),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(json(STORED_TOKENS), state.storedJson(SIGN_IN_REQUEST))
  }

  @Test
  fun storeSignInResponse_configuredClientSecret_isNotStored() {
    val config = AuthConfig(AUTH_CODE, CONFIDENTIAL)
    val state = State()

    val outcome = config.storeSignInResponse(TOKENS, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    val credential = assertIs<AuthCredential.OAuth2>(state[config.signInResponseStateKey])
    assertEquals("cid", credential.clientId)
    assertNull(credential.clientSecret)
  }

  @Test
  fun storeSignInResponse_credentialResponse_storesItWithTheRequestsFields() {
    val state = State()

    val outcome = SIGN_IN_REQUEST.storeSignInResponse(TOKENS, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(json(STORED_TOKENS), state.storedJson(SIGN_IN_REQUEST))
  }

  @Test
  fun storeSignInResponse_oauth2ResponseWithoutAccessToken_needsCodeExchange() {
    // ADK can't exchange an authorization code yet, so neither response is stored.
    val cases =
      listOf(
        AuthConfig(AUTH_CODE, CONFIDENTIAL) to
          mapOf("authType" to "oauth2", "oauth2" to mapOf("authCode" to "code")),
        SIGN_IN_REQUEST to mapOf("authType" to "oauth2", "oauth2" to mapOf("accessToken" to "")),
      )

    for ((config, response) in cases) {
      val state = State()

      val outcome = config.storeSignInResponse(response, state)

      assertEquals(StoreSignInResponseOutcome.NEEDS_CODE_EXCHANGE, outcome)
      assertTrue(state.isEmpty())
    }
  }

  @Test
  fun storeSignInResponse_httpBasicResponseWithOnlyAUsername_storesIt() {
    // RFC 7617 allows an empty password, and some APIs take their key as the username.
    val config = AuthConfig(AuthScheme.Http.basic(), credentialKey = CredentialKey("basic"))
    val response =
      mapOf(
        "authType" to "http",
        "http" to mapOf("scheme" to "basic", "credentials" to mapOf("username" to "user-key")),
      )

    val outcome = config.storeSignInResponse(response, State())

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
  }

  @Test
  fun storeSignInResponse_httpBasicResponseWithTheRequestsUsername_storesNothing() {
    // The request's own username is in its output, so posting that output back isn't a sign-in.
    val requested =
      AuthCredential.Http(
        scheme = "basic",
        credentials = AuthCredential.Http.Credentials(username = "ada"),
      )
    val config =
      AuthConfig(
        AuthScheme.Http.basic(),
        rawAuthCredential = requested,
        credentialKey = CredentialKey("basic"),
      )
    val state = State()
    val response =
      mapOf(
        "authType" to "http",
        "http" to mapOf("scheme" to "basic", "credentials" to mapOf("username" to "ada")),
      )

    val outcome = config.storeSignInResponse(response, state)

    assertEquals(StoreSignInResponseOutcome.NO_CREDENTIAL, outcome)
    assertTrue(state.isEmpty())
  }

  @Test
  fun storeSignInResponse_httpResponseWithOnlyAHeader_storesIt() {
    // A header value is secret too, so a credential holding only one has signed in.
    val config = AuthConfig(AuthScheme.Http.bearer(), credentialKey = CredentialKey("http"))
    val state = State()
    val response =
      mapOf(
        "authType" to "http",
        "http" to
          mapOf(
            "scheme" to "bearer",
            "credentials" to emptyMap<String, Any?>(),
            "additionalHeaders" to mapOf("X-Key" to "user-key"),
          ),
      )

    val outcome = config.storeSignInResponse(response, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
  }

  @Test
  fun storeSignInResponse_responseNamingTokenEndpointAuthMethod_keepsIt() {
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = CLIENT_SECRET_POST_REQUEST)
    val response =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to mapOf("accessToken" to "at", "tokenEndpointAuthMethod" to "client_secret_basic"),
      )
    val state = State()

    assertEquals(StoreSignInResponseOutcome.STORED, config.storeSignInResponse(response, state))
    assertEquals(
      AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
      assertIs<AuthCredential.OAuth2>(state[config.signInResponseStateKey]).tokenEndpointAuthMethod,
    )
  }

  @Test
  fun storeSignInResponse_responseWithClientFields_pinsTheClientId() {
    // The response's redirect URI is kept, and its client secret dropped.
    val response =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to
          mapOf(
            "accessToken" to "at",
            "clientId" to "evil",
            "clientSecret" to "evil-sec",
            "redirectUri" to "https://evil/cb",
          ),
      )
    val state = State()

    val outcome = SIGN_IN_REQUEST.storeSignInResponse(response, state)

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      json(
        """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
          "redirectUri": "https://evil/cb", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      state.storedJson(SIGN_IN_REQUEST),
    )
  }

  @Test
  fun storeSignInResponse_responseWithCodeVerifier_keepsIt() {
    val exchanged =
      SIGN_IN_REQUEST.exchangedAuthCredential
        .oauth2()
        .copy(codeVerifier = Secret(VERIFIER), codeChallengeMethod = "S256")
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = exchanged)
    val responseVerifier = "r".repeat(43)
    val state = State()

    val outcome =
      config.storeSignInResponse(
        mapOf(
          "authType" to "openIdConnect",
          "oauth2" to
            mapOf(
              "accessToken" to "at",
              "codeVerifier" to responseVerifier,
              "codeChallengeMethod" to "plain",
            ),
        ),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      json(
        """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
          "codeChallengeMethod": "plain", "codeVerifier": "$responseVerifier",
          "redirectUri": "https://app/cb", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      state.storedJson(config),
    )
  }

  @Test
  fun storeSignInResponse_responseWithoutCodeVerifier_takesTheRequestsVerifier() {
    val exchanged =
      SIGN_IN_REQUEST.exchangedAuthCredential
        .oauth2()
        .copy(codeVerifier = Secret(VERIFIER), codeChallengeMethod = "S256")
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = exchanged)
    val state = State()

    val outcome =
      config.storeSignInResponse(
        mapOf("authType" to "openIdConnect", "oauth2" to mapOf("accessToken" to "at")),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      json(
        """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
          "codeChallengeMethod": "S256", "codeVerifier": "$VERIFIER",
          "redirectUri": "https://app/cb", "tokenEndpointAuthMethod": "client_secret_basic"}}"""
      ),
      state.storedJson(config),
    )
  }

  @Test
  fun storeSignInResponse_responseWithoutCredential_storesNothing() {
    val responses = listOf(mapOf("authScheme" to HTTP_BEARER), mapOf("foo" to 1), "not a map", null)

    for (response in responses) {
      val state = State()

      val outcome = SIGN_IN_REQUEST.storeSignInResponse(response, state)

      assertEquals(StoreSignInResponseOutcome.NO_CREDENTIAL, outcome)
      assertTrue(state.isEmpty())
    }
  }

  @Test
  fun storeSignInResponse_responseWithoutTokenEndpointAuthMethod_takesTheRequestsMethod() {
    val config = SIGN_IN_REQUEST.copy(exchangedAuthCredential = CLIENT_SECRET_POST_REQUEST)
    val responses =
      listOf(TOKENS, mapOf("authScheme" to HTTP_BEARER, "exchangedAuthCredential" to TOKENS))

    for (response in responses) {
      val state = State()

      assertEquals(StoreSignInResponseOutcome.STORED, config.storeSignInResponse(response, state))
      assertEquals(
        AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_POST,
        assertIs<AuthCredential.OAuth2>(state[config.signInResponseStateKey])
          .tokenEndpointAuthMethod,
      )
    }
  }

  @Test
  fun storeSignInResponse_tokenlessOAuth2ResponseOnHttpScheme_storesIt() {
    // Only OAuth2 and OpenID Connect schemes exchange a code.
    val config = AuthConfig(AuthScheme.Http.bearer())
    val state = State()

    val outcome =
      config.storeSignInResponse(
        mapOf("authType" to "oauth2", "oauth2" to mapOf("authCode" to "code")),
        state,
      )

    assertEquals(StoreSignInResponseOutcome.STORED, outcome)
    assertEquals(
      AuthCredential.OAuth2(authCode = Secret("code"), tokenEndpointAuthMethod = null),
      state[config.signInResponseStateKey],
    )
  }

  @Test
  fun withCompletedSignInResponses_bareCredentialAnsweringAnotherRequest_leavesItEmpty() {
    val message = responseMessage(PLAIN_API_KEY, id = "req-2")

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_bareCredentialReadFromJson_takesTheConfigOfItsRequest() {
    // As an HTTP client sends it, which decoding wraps under exchangedAuthCredential.
    val part =
      adkJson.decodeFromString(
        Part.serializer(),
        """{"functionResponse": {"name": "adk_request_credential", "id": "req-1",
          "response": {"authType": "apiKey", "apiKey": "k"}}}""",
      )
    val message = Content(role = Role.USER, parts = listOf(part))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(API_KEY_RESPONSE, completed.response())
  }

  @Test
  fun withCompletedSignInResponses_bareCredentialWithoutItsRequest_leavesItEmpty() {
    val message = responseMessage(PLAIN_API_KEY)

    assertEquals(emptyMap(), message.withCompletedSignInResponses(emptyList()).response())
  }

  @Test
  fun withCompletedSignInResponses_bareCredential_takesTheConfigOfItsRequest() {
    val message = responseMessage(PLAIN_API_KEY)

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(API_KEY_RESPONSE, completed.response())
  }

  @Test
  fun withCompletedSignInResponses_bareKeyForAnHttpBasicRequest_leavesItEmpty() {
    // HTTP basic needs a username and password, not a bare key.
    val basic = AuthConfig(AuthScheme.Http.basic(), credentialKey = CredentialKey("basic"))
    val message = responseMessage(mapOf("result" to "k"))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(basic)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_bareKeyForAnHttpBearerRequest_isItsToken() {
    val config = AuthConfig(AuthScheme.Http.bearer(), credentialKey = CredentialKey("bearer"))
    val message = responseMessage(mapOf("result" to "tok"))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(config)))

    assertEquals(
      config.copy(exchangedAuthCredential = AuthCredential.Http.bearer("tok")).toSignInResponse(),
      completed.response(),
    )
  }

  @Test
  fun withCompletedSignInResponses_bareKeyForAnOAuth2Request_leavesItEmpty() {
    // OAuth2 needs a token exchange, not a bare key.
    val message = responseMessage(mapOf("result" to "k"))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(SIGN_IN_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_bareKeyReadFromJson_takesTheConfigOfItsRequest() {
    // As an HTTP client sends it, which decoding holds as a Secret.
    val part =
      adkJson.decodeFromString(
        Part.serializer(),
        """{"functionResponse": {"name": "adk_request_credential", "id": "req-1",
          "response": {"result": "k"}}}""",
      )
    val message = Content(role = Role.USER, parts = listOf(part))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(API_KEY_RESPONSE, completed.response())
  }

  @Test
  fun withCompletedSignInResponses_bareKeyUnderAnotherName_takesTheConfigOfItsRequest() {
    // A workflow node's answer: found by the id of the request it answers.
    val message = responseMessage(mapOf("result" to "k"), name = "answer")

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(API_KEY_RESPONSE, completed.response())
    assertEquals(
      FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
      completed.parts.single().functionResponse?.name,
    )
  }

  @Test
  fun withCompletedSignInResponses_configAsJsonTextUnderResult_leavesItEmpty() {
    // A web client can send its answer as text; a config in it is not a key.
    val message = responseMessage(mapOf("result" to """{"authType":"apiKey","apiKey":"k"}"""))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_emptySecretKey_leavesItEmpty() {
    val message = responseMessage(mapOf("result" to Secret("")))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_emptyStringKey_leavesItEmpty() {
    val message = responseMessage(mapOf("result" to ""))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_intKey_takesTheConfigOfItsRequest() {
    val message = responseMessage(mapOf("result" to 42))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(
      API_KEY_REQUEST.copy(exchangedAuthCredential = AuthCredential.ApiKey(Secret("42")))
        .toSignInResponse(),
      completed.response(),
    )
  }

  @Test
  fun withCompletedSignInResponses_latestRequestWithTheIdIsMalformed_leavesAnAnswerByIdEmpty() {
    // Ids repeat across runs, so the latest request decides, even when it can't be read.
    val malformed =
      requestEvent(API_KEY_REQUEST).let { event ->
        val call =
          assertNotNull(assertNotNull(event.content).parts.single().functionCall)
            .copy(args = mapOf("x" to 1))
        event.copy(content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = call))))
      }
    val message = responseMessage(mapOf("result" to "k"), name = "answer")

    val completed =
      message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST), malformed))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_longKey_takesTheConfigOfItsRequest() {
    val message = responseMessage(mapOf("result" to 42L))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(
      API_KEY_REQUEST.copy(exchangedAuthCredential = AuthCredential.ApiKey(Secret("42")))
        .toSignInResponse(),
      completed.response(),
    )
  }

  @Test
  fun withCompletedSignInResponses_neitherConfigNorCredential_leavesItEmpty() {
    val message = responseMessage(mapOf("token" to "k"))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_newerCallReusingTheId_leavesAnAnswerToItAsSent() {
    // Ids repeat across runs, so an answer to a newer input request isn't one to the older sign-in.
    val input =
      FunctionCall(
        name = FunctionCall.REQUEST_INPUT_FUNCTION_CALL_NAME,
        args = emptyMap(),
        id = "req-1",
      )
    val inputRequest =
      Event(
        author = "agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = input))),
        longRunningToolIds = setOf("req-1"),
      )
    val message =
      responseMessage(mapOf("result" to "k"), name = FunctionCall.REQUEST_INPUT_FUNCTION_CALL_NAME)

    val completed =
      message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST), inputRequest))

    assertEquals(message, completed)
  }

  @Test
  fun withCompletedSignInResponses_otherFunctionResponse_keepsIt() {
    val message = responseMessage(PLAIN_API_KEY, name = "read_data", id = "call-2")

    assertEquals(
      message,
      message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST))),
    )
  }

  @Test
  fun withCompletedSignInResponses_plainConfig_holdsItsCredentialTyped() {
    val message = responseMessage(PLAIN_API_KEY_CONFIG)

    val completed = message.withCompletedSignInResponses(emptyList())

    assertEquals(API_KEY_RESPONSE, completed.response())
  }

  @Test
  fun withCompletedSignInResponses_redactedSecretKey_leavesItEmpty() {
    val message = responseMessage(mapOf("result" to Secret("<redacted>")))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_redactedStringKey_leavesItEmpty() {
    val message = responseMessage(mapOf("result" to "<redacted>"))

    val completed = message.withCompletedSignInResponses(listOf(requestEvent(API_KEY_REQUEST)))

    assertEquals(emptyMap(), completed.response())
  }

  @Test
  fun withCompletedSignInResponses_requestTheModelWrote_leavesAnAnswerUnderAnotherNameAsSent() {
    // A model's call to a tool that isn't long-running carries no long-running mark.
    val modelWritten = requestEvent(API_KEY_REQUEST).copy(longRunningToolIds = emptySet())
    val message = responseMessage(mapOf("result" to "k"), name = "answer")

    val completed = message.withCompletedSignInResponses(listOf(modelWritten))

    assertEquals(message, completed)
  }

  @OptIn(SecretAccess::class)
  @Test
  fun toSignInResponse_config_writesTheSerializersJson() {
    val config = API_KEY_REQUEST.copy(exchangedAuthCredential = AuthCredential.ApiKey(Secret("k")))

    assertEquals(
      adkSecretRevealingJson.encodeToJsonElement(AuthConfigSerializer, config),
      anyToJsonElement(config.toSignInResponse(), adkSecretRevealingJson),
    )
  }

  @Test
  fun withDecodedSignInResponse_plainConfig_holdsItsCredentialTyped() {
    val response = responseMessage(PLAIN_API_KEY_CONFIG).functionResponse()

    assertEquals(API_KEY_RESPONSE, response.withDecodedSignInResponse().response)
  }

  @Test
  fun withDecodedSignInResponse_bareCredential_holdsItTyped() {
    val response = responseMessage(PLAIN_API_KEY).functionResponse()

    assertEquals(
      mapOf("exchangedAuthCredential" to AuthCredential.ApiKey(Secret("k"))),
      response.withDecodedSignInResponse().response,
    )
  }

  @Test
  fun withDecodedSignInResponse_bareKey_holdsItAsASecret() {
    val response = responseMessage(mapOf("result" to "k")).functionResponse()

    assertEquals(mapOf("result" to Secret("k")), response.withDecodedSignInResponse().response)
  }

  @Test
  fun withDecodedSignInResponse_neitherConfigNorCredential_leavesItEmpty() {
    val response = responseMessage(mapOf("token" to "k")).functionResponse()

    assertEquals(emptyMap(), response.withDecodedSignInResponse().response)
  }

  @Test
  fun withDecodedSignInResponse_otherFunctionResponse_isUnchanged() {
    val response = responseMessage(PLAIN_API_KEY, name = "read_data").functionResponse()

    assertEquals(response, response.withDecodedSignInResponse())
  }

  @Test
  fun part_signInResponseReadFromJson_outputLeavesItsKeyOut() {
    val stored =
      adkSecretRevealingJson.encodeToString(
        Part.serializer(),
        responseMessage(PLAIN_API_KEY_CONFIG).parts.single(),
      )

    val part = adkJson.decodeFromString(Part.serializer(), stored)

    assertEquals(API_KEY_RESPONSE, part.functionResponse?.response)
    assertFalse(adkJson.encodeToString(Part.serializer(), part).contains("\"k\""))
  }

  private companion object {
    val API_KEY = AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER)

    val API_KEY_REQUEST = AuthConfig(API_KEY, credentialKey = CredentialKey("key"))

    val PLAIN_API_KEY = mapOf("authType" to "apiKey", "apiKey" to "k")

    /** [API_KEY_REQUEST] answered with key "k", as plain values. */
    val PLAIN_API_KEY_CONFIG =
      mapOf(
        "authScheme" to mapOf("type" to "apiKey", "in" to "header", "name" to "X-Key"),
        "exchangedAuthCredential" to PLAIN_API_KEY,
        "credentialKey" to "key",
      )

    /** [API_KEY_REQUEST] answered with key "k", as its sign-in response holds it. */
    val API_KEY_RESPONSE =
      mapOf(
        "authScheme" to mapOf("type" to "apiKey", "in" to "header", "name" to "X-Key"),
        "exchangedAuthCredential" to AuthCredential.ApiKey(Secret("k")),
        "credentialKey" to "key",
      )

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

    val HTTP_BEARER = mapOf("type" to "http", "scheme" to "bearer")

    /** A sign-in request ADK recorded for [OIDC], without the client secret. */
    val SIGN_IN_REQUEST =
      AuthConfig(
        authScheme = OIDC,
        rawAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            redirectUri = "https://app/cb",
          ),
        exchangedAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            authUri = "https://o/auth?state=s",
            state = "s",
            redirectUri = "https://app/cb",
          ),
      )

    /** [SIGN_IN_REQUEST]'s exchanged credential for a client that authenticates in the body. */
    val CLIENT_SECRET_POST_REQUEST =
      SIGN_IN_REQUEST.exchangedAuthCredential
        .oauth2()
        .copy(
          tokenEndpointAuthMethod = AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_POST
        )

    val TOKENS =
      mapOf(
        "authType" to "openIdConnect",
        "oauth2" to mapOf("accessToken" to "at", "refreshToken" to "rt", "expiresAt" to 1767225600L),
      )

    /** What is stored for [TOKENS] in response to [SIGN_IN_REQUEST], in ADK Kotlin's JSON. */
    const val STORED_TOKENS =
      """{"authType": "openIdConnect", "oauth2": {"accessToken": "at", "clientId": "cid",
        "expiresAt": 1767225600, "redirectUri": "https://app/cb", "refreshToken": "rt",
        "tokenEndpointAuthMethod": "client_secret_basic"}}"""

    val VERIFIER = "v".repeat(43)

    /** Returns a user message answering credential request [id] with [response]. */
    fun responseMessage(
      response: Map<String, Any?>,
      name: String = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
      id: String = "req-1",
    ): Content =
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(functionResponse = FunctionResponse(name = name, id = id, response = response))
          ),
      )

    /**
     * Returns the agent's event asking for [config]'s credential with credential request `req-1`.
     */
    fun requestEvent(config: AuthConfig): Event =
      Event(
        author = "agent",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(
                  functionCall =
                    FunctionCall(
                      name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                      args = RequestCredentialArgs("call-1", config).toMap(),
                      id = "req-1",
                    )
                )
              ),
          ),
        longRunningToolIds = setOf("req-1"),
      )

    fun Content.response(): Map<String, Any?>? = parts.single().functionResponse?.response

    fun Content.functionResponse(): FunctionResponse =
      assertNotNull(parts.single().functionResponse)

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** Returns the credential stored as [config]'s sign-in response, in ADK Kotlin's JSON. */
    fun State.storedJson(config: AuthConfig): JsonElement? =
      (this[config.signInResponseStateKey] as AuthCredential?)?.let {
        adkSecretRevealingJson.encodeToJsonElement(AuthCredentialSerializer, it)
      }

    fun AuthCredential?.oauth2(): AuthCredential.OAuth2 = this as AuthCredential.OAuth2
  }
}
