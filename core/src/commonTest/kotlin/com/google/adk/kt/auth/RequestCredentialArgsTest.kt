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
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.descriptors.elementNames
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
class RequestCredentialArgsTest {

  @Test
  fun fromMap_malformedArgs_returnsNull() {
    val config = CALL_ARGS.getValue("authConfig")
    val inputs =
      listOf(
        mapOf("authConfig" to config),
        mapOf("functionCallId" to "call-1"),
        mapOf("functionCallId" to "call-1", "authConfig" to "x"),
        mapOf("functionCallId" to 1, "authConfig" to config),
        // A scheme without a type.
        mapOf(
          "functionCallId" to "call-1",
          "authConfig" to mapOf("authScheme" to mapOf("scheme" to "bearer"), "credentialKey" to "k"),
        ),
        // A value JSON can't hold.
        mapOf("functionCallId" to "call-1", "authConfig" to Any()),
      )

    inputs.forEachIndexed { i, input ->
      assertNull(RequestCredentialArgs.fromMap(input), "input #$i")
    }
  }

  @Test
  fun fromMap_snakeCaseArgs_readsThem() {
    val args =
      mapOf("function_call_id" to "call-1", "auth_config" to CALL_ARGS.getValue("authConfig"))

    assertEquals(ARGS, RequestCredentialArgs.fromMap(args))
  }

  @Test
  fun fromMap_wellFormedArgs_readsThem() {
    assertEquals(ARGS, RequestCredentialArgs.fromMap(CALL_ARGS))
  }

  @Test
  fun toMap_keepsTheConfigTyped() {
    assertEquals(mapOf("functionCallId" to "call-1", "authConfig" to ARGS.authConfig), ARGS.toMap())
  }

  @Test
  fun toMap_writesTheSerializersJson() {
    val args = ARGS.copy(message = "Sign in.")

    assertEquals(
      adkSecretRevealingJson.encodeToJsonElement(RequestCredentialArgs.serializer(), args),
      anyToJsonElement(args.toMap(), adkSecretRevealingJson),
    )
    // Every field, so that one added later can't be left out of the map unnoticed.
    assertEquals(
      RequestCredentialArgs.serializer().descriptor.elementNames.toSet(),
      args.toMap().keys,
    )
  }

  @Test
  fun withDecodedSignInRequest_plainArgs_typesTheConfig() {
    val call = FunctionCall(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, CALL_ARGS, "call-1")

    assertEquals(ARGS.toMap(), call.withDecodedSignInRequest().args)
  }

  @Test
  fun withDecodedSignInRequest_malformedArgs_dropsThem() {
    val call =
      FunctionCall(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, mapOf("apiKey" to "k"), "call-1")

    assertEquals(emptyMap(), call.withDecodedSignInRequest().args)
  }

  @Test
  fun withDecodedSignInRequest_otherCall_isUnchanged() {
    val call = FunctionCall("lookup", CALL_ARGS, "call-1")

    assertEquals(call, call.withDecodedSignInRequest())
  }

  @Test
  fun part_signInRequestReadFromJson_outputLeavesItsSecretsOut() {
    val config =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        AuthCredential.ApiKey(Secret("k")),
        credentialKey = CredentialKey("key"),
      )
    val args = RequestCredentialArgs("call-1", config).toMap()
    val stored =
      adkSecretRevealingJson.encodeToString(
        Part.serializer(),
        Part(functionCall = FunctionCall(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME, args, "c")),
      )

    val part = adkJson.decodeFromString(Part.serializer(), stored)

    assertEquals(args, part.functionCall?.args)
    assertFalse(adkJson.encodeToString(Part.serializer(), part).contains("\"k\""))
  }

  private companion object {
    val ARGS =
      RequestCredentialArgs(
        "call-1",
        AuthConfig(AuthScheme.Http.bearer(), credentialKey = CredentialKey("k")),
      )

    /** [ARGS] as function call args. */
    val CALL_ARGS =
      mapOf(
        "functionCallId" to "call-1",
        "authConfig" to
          mapOf(
            "authScheme" to mapOf("type" to "http", "scheme" to "bearer"),
            "credentialKey" to "k",
          ),
      )
  }
}
