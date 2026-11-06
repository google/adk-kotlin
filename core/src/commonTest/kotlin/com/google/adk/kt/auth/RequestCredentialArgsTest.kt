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
import org.junit.Test

@OptIn(ExperimentalAuthApi::class)
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
  fun toMap_writesCallArgs() {
    assertEquals(CALL_ARGS, ARGS.toMap())
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
