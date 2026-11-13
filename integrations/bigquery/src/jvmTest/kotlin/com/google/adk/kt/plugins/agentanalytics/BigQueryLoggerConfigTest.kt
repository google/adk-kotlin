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

package com.google.adk.kt.plugins.agentanalytics

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.auth.Credentials
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.kotlin.mock

class BigQueryLoggerConfigTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val config =
      BigQueryLoggerConfig(
        projectId = "test-project",
        datasetId = "test-dataset",
        enabled = false,
        location = "EU",
        tableName = "test-table",
        credentials = mock<Credentials>(),
        eventAllowlist = setOf("LLM_REQUEST"),
        eventDenylist = setOf("STATE_DELTA"),
        maxContentLength = 1024,
        clusteringFields = listOf("agent"),
        logMultiModalContent = false,
        logSessionMetadata = false,
        customTags = mapOf("env" to "test"),
        autoSchemaUpgrade = false,
        createViews = true,
        viewPrefix = "custom",
        contentFormatter = { content, _ -> content },
        retryConfig = RetryConfig(maxRetries = 1),
        batchSize = 50,
        batchFlushInterval = Duration.ofMillis(500),
        shutdownTimeout = Duration.ofSeconds(3),
        queueMaxSize = 200,
        flushOnRunEnd = false,
        gcsBucketName = "test-bucket",
        connectionId = "us.my-connection",
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(enabled = true), config.toBuilder().enabled(true).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_defaultsMatchTheConstructor() {
    assertEquals(
      BigQueryLoggerConfig("p", "d"),
      BigQueryLoggerConfig.builder().projectId("p").datasetId("d").build(),
    )
  }

  @Test
  fun gcsOffloadDefaults_areNullByDefault() {
    val config = BigQueryLoggerConfig("p", "d")

    assertNull(config.gcsBucketName)
    assertNull(config.connectionId)
  }

  @Test
  fun blankGcsBucketName_isRejected() {
    for (blank in listOf("", "  ")) {
      assertFailsWith<IllegalArgumentException> {
        BigQueryLoggerConfig("p", "d", gcsBucketName = blank)
      }
    }
  }

  @Test
  fun batchingDefaults_matchPythonAdk() {
    val config = BigQueryLoggerConfig("p", "d")

    assertEquals(RetryConfig(), config.retryConfig)
    assertEquals(1, config.batchSize)
    assertEquals(Duration.ofSeconds(1), config.batchFlushInterval)
    assertEquals(Duration.ofSeconds(10), config.shutdownTimeout)
    assertEquals(10_000, config.queueMaxSize)
    assertTrue(config.flushOnRunEnd)
  }

  @Test
  fun invalidBatchingValues_areRejected() {
    assertFailsWith<IllegalArgumentException> { BigQueryLoggerConfig("p", "d", batchSize = 0) }
    assertFailsWith<IllegalArgumentException> {
      BigQueryLoggerConfig("p", "d", batchFlushInterval = Duration.ZERO)
    }
    assertFailsWith<IllegalArgumentException> {
      BigQueryLoggerConfig("p", "d", batchFlushInterval = Duration.ofSeconds(-1))
    }
    assertFailsWith<IllegalArgumentException> {
      BigQueryLoggerConfig("p", "d", shutdownTimeout = Duration.ZERO)
    }
    assertFailsWith<IllegalArgumentException> { BigQueryLoggerConfig("p", "d", queueMaxSize = 0) }
  }

  @Test
  fun maxContentLength_acceptsUnlimitedOrPositive() {
    for (valid in listOf(-1, 1, 500 * 1024)) {
      assertEquals(valid, BigQueryLoggerConfig("p", "d", maxContentLength = valid).maxContentLength)
    }
  }

  @Test
  fun maxContentLength_rejectsZeroAndOtherNegatives() {
    assertFailsWith<IllegalArgumentException> {
      BigQueryLoggerConfig("p", "d", maxContentLength = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      BigQueryLoggerConfig("p", "d", maxContentLength = -2)
    }
  }

  @Test
  fun javaConstructor_keepsTheSixArgumentOverload() {
    val constructor =
      BigQueryLoggerConfig::class
        .java
        .getConstructor(
          String::class.java,
          String::class.java,
          Boolean::class.javaPrimitiveType,
          String::class.java,
          String::class.java,
          Credentials::class.java,
        )

    assertEquals(
      BigQueryLoggerConfig("p", "d", enabled = false, location = "EU", tableName = "t"),
      constructor.newInstance("p", "d", false, "EU", "t", null),
    )
  }
}
