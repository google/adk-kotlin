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

package com.google.adk.kt.integrations.secretmanager

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Everything here stays on the local machine: no test resolves Application Default Credentials,
 * opens a transport, or reads a secret.
 */
class SecretManagerClientTest {

  private val marker = "do-not-log-this-marker"

  @Test
  fun bothAKeyfileAndATokenIsRefused() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        SecretManagerClient(serviceAccountJson = keyfile(), authToken = "token")
      }

    assertThat(failure).hasMessageThat().contains("not both")
  }

  @Test
  fun anEmptyKeyfileIsTreatedAsAbsentSoATokenIsStillAccepted() {
    SecretManagerClient(serviceAccountJson = "", authToken = "token").use {
      assertThat(it.lazyServiceClient.isInitialized()).isFalse()
    }
  }

  @Test
  fun aKeyfileThatCannotBeReadIsRefusedAtConstruction() {
    val failure =
      assertFailsWith<IllegalArgumentException> { SecretManagerClient(serviceAccountJson = "{}") }

    assertThat(failure).hasCauseThat().isNotNull()
  }

  /** The credential library quotes the `type` field back, and that must not reach the message. */
  @Test
  fun theRefusalDoesNotQuoteTheKeyfile() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        SecretManagerClient(serviceAccountJson = """{"type":"$marker"}""")
      }

    assertThat(failure).hasMessageThat().doesNotContain(marker)
  }

  @Test
  fun aReadableKeyfileOpensNoTransport() {
    SecretManagerClient(serviceAccountJson = keyfile()).use {
      assertThat(it.lazyServiceClient.isInitialized()).isFalse()
    }
  }

  @Test
  fun readingAfterCloseFails() = runBlocking {
    val client = SecretManagerClient(authToken = "token")
    client.close()

    assertFailsWith<IllegalStateException> {
      client.getSecret("projects/p/secrets/s/versions/latest")
    }
    Unit
  }

  @Test
  fun apiEndpointPicksTheTemplateTheCallerAskedFor() {
    assertThat(apiEndpoint("europe-west4", useMtls = false))
      .isEqualTo("secretmanager.europe-west4.rep.googleapis.com:443")
    assertThat(apiEndpoint("europe-west4", useMtls = true))
      .isEqualTo("secretmanager.europe-west4.rep.mtls.googleapis.com:443")
  }

  @Test
  fun anExplicitMtlsSettingDecidesOnItsOwn() {
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = "always", useClientCertificate = "false"))
      .isTrue()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = "ALWAYS", useClientCertificate = null))
      .isTrue()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = "never", useClientCertificate = "true"))
      .isFalse()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = "Never", useClientCertificate = "true"))
      .isFalse()
  }

  @Test
  fun otherwiseTheClientCertificateSettingDecides() {
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = null, useClientCertificate = "true")).isTrue()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = "auto", useClientCertificate = "TRUE"))
      .isTrue()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = "nonsense", useClientCertificate = "true"))
      .isTrue()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = null, useClientCertificate = "false"))
      .isFalse()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = null, useClientCertificate = "1")).isFalse()
    assertThat(useMtlsEndpoint(mtlsEndpointSetting = null, useClientCertificate = null)).isFalse()
  }

  /** A syntactically complete service account keyfile, with a key pair generated for this run. */
  private fun keyfile(): String {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    // One unbroken line: a raw newline inside a JSON string literal would not parse.
    val pem = Base64.getEncoder().encodeToString(keyPair.private.encoded)
    return """
      {
        "type": "service_account",
        "project_id": "test-project",
        "private_key_id": "test-key-id",
        "private_key": "-----BEGIN PRIVATE KEY-----\n$pem\n-----END PRIVATE KEY-----\n",
        "client_email": "test@test-project.iam.gserviceaccount.com",
        "client_id": "1234567890"
      }
      """
      .trimIndent()
  }
}
