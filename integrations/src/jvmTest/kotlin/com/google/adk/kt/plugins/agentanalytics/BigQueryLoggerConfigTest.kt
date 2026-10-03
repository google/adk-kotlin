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
import kotlin.test.Test
import kotlin.test.assertEquals
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
        connectionId = "us.test-connection",
        contentFormatter = { content, _ -> content },
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(enabled = true), config.toBuilder().enabled(true).build())
  }
}
