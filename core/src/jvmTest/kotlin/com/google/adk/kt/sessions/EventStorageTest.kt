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

@file:OptIn(ExperimentalAuthApi::class)

package com.google.adk.kt.sessions

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.CredentialKey
import com.google.adk.kt.auth.RequestCredentialArgs
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.auth.SignInRequest
import com.google.adk.kt.auth.adkSecretRevealingJson
import com.google.adk.kt.auth.findCredentialRequest
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testApiKeyConfig
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals

class EventStorageTest {

  @Test
  fun forStorage_functionCallWithATypedCredential_leavesItsSecretOut() {
    val call = FunctionCall("f", mapOf("key" to AuthCredential.ApiKey(Secret("sk-123"))))
    val event = Event(author = "agent", content = modelMessage(Part(functionCall = call)))

    val stored = event.forStorage()

    assertEquals(
      mapOf("key" to mapOf("authType" to "apiKey")),
      stored.content!!.parts.single().functionCall!!.args,
    )
  }

  @Test
  fun forStorage_functionResponseWithATypedCredential_leavesItsSecretOut() {
    val response = FunctionResponse("f", mapOf("key" to AuthCredential.ApiKey(Secret("sk-123"))))
    val event = Event(author = "user", content = userMessage(Part(functionResponse = response)))

    val stored = event.forStorage()

    assertEquals(
      mapOf("key" to mapOf("authType" to "apiKey")),
      stored.content!!.parts.single().functionResponse!!.response,
    )
  }

  @Test
  fun forStorage_compactionWithATypedCredential_leavesItsSecretOut() {
    val call = FunctionCall("f", mapOf("key" to AuthCredential.ApiKey(Secret("sk-123"))))
    val compaction =
      EventCompaction(
        startTimestamp = 1,
        endTimestamp = 2,
        compactedContent = modelMessage(Part(functionCall = call)),
      )
    val event = Event(author = "agent", actions = EventActions(compaction = compaction))

    val stored = event.forStorage()

    assertEquals(
      mapOf("key" to mapOf("authType" to "apiKey")),
      stored.actions.compaction!!.compactedContent.parts.single().functionCall!!.args,
    )
  }

  @Test
  fun forStorage_customMetadataWithATypedCredential_leavesItsSecretOut() {
    val credential = AuthCredential.ApiKey(Secret("sk-123"))
    val event = Event(author = "agent", customMetadata = mapOf("key" to credential, "turn" to 2L))

    val stored = event.forStorage()

    assertEquals(mapOf("key" to mapOf("authType" to "apiKey"), "turn" to 2L), stored.customMetadata)
  }

  @Test
  fun forStorage_toolConfirmationPayloadWithATypedCredential_leavesItsSecretOut() {
    val payload = mapOf("key" to AuthCredential.ApiKey(Secret("sk-123")))
    val actions =
      EventActions(
        requestedToolConfirmations =
          mutableMapOf(
            "call-1" to ToolConfirmation(confirmed = false, payload = payload, hint = "ok?")
          )
      )
    val event = Event(author = "agent", actions = actions)

    val stored = event.forStorage()

    assertEquals(
      mapOf(
        "call-1" to
          ToolConfirmation(
            confirmed = false,
            payload = mapOf("key" to mapOf("authType" to "apiKey")),
            hint = "ok?",
          )
      ),
      stored.actions.requestedToolConfirmations,
    )
  }

  @Test
  fun forStorage_stateDeltaOutputAndRequestedAuthConfigs_keepTheirCredentials() {
    val credential = AuthCredential.ApiKey(Secret("sk-123"))
    val config = testApiKeyConfig(rawKey = "sk-123")
    val event =
      Event(
        author = "agent",
        content = modelMessage("text"),
        output = mapOf("key" to credential),
        actions =
          EventActions(requestedAuthConfigs = mutableMapOf("call-1" to config)).apply {
            stateDelta["key"] = credential
          },
      )

    val stored = event.forStorage()

    assertEquals(mapOf("key" to credential), stored.output)
    assertEquals(mapOf<String, Any>("key" to credential), stored.actions.stateDelta)
    assertEquals(mapOf("call-1" to config), stored.actions.requestedAuthConfigs)
  }

  @Test
  fun forStorage_contentWithoutCredentials_isUnchanged() {
    val call = FunctionCall("f", mapOf("query" to "ADK", "limit" to 3L), id = "call-1")
    val event =
      Event(
        author = "agent",
        content = modelMessage(Part(text = "Looking"), Part(functionCall = call)),
      )

    val stored = event.forStorage()

    assertEquals(event, stored)
  }

  @Test
  @OptIn(FrameworkInternalApi::class, SecretAccess::class)
  fun forStorage_signInRequest_stillMatchesItsConfigAfterAReload() {
    val config =
      AuthConfig(
        AuthScheme.OpenIdConnect(
          authorizationEndpoint = "https://a/auth",
          tokenEndpoint = "https://a/token",
          scopes = listOf("openid"),
        ),
        exchangedAuthCredential =
          AuthCredential.OAuth2(clientId = "cid", codeVerifier = Secret("verifier-sentinel")),
        credentialKey = CredentialKey("key"),
      )
    val args = RequestCredentialArgs("call-1", config).toMap()
    val call = FunctionCall(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, args, id = "req-1")
    val event = Event(author = "agent", content = modelMessage(Part(functionCall = call)))

    val json = adkSecretRevealingJson.encodeToString(Event.serializer(), event.forStorage())
    val reloaded = adkJson.decodeFromString(Event.serializer(), json)

    assertEquals(
      RequestCredentialArgs("call-1", config),
      listOf(reloaded).findCredentialRequest("req-1", mapOf("call-1" to listOf(config))),
    )
  }

  @Test
  fun forStorage_signInResponse_keepsItsToken() {
    val request = SignInRequest("req-1", testApiKeyConfig(credentialKey = "key"))
    val event =
      Event(
        author = "user",
        content = request.createResponse(AuthCredential.ApiKey(Secret("sk-123"))),
      )

    val stored = event.forStorage()

    assertEquals(event, stored)
  }
}
