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

import com.google.auth.Credentials
import kotlin.jvm.JvmStatic

/** Configuration for the BigQueryAgentAnalyticsPlugin. */
data class BigQueryLoggerConfig(
  val projectId: String,
  val datasetId: String,
  val enabled: Boolean = true,
  val location: String = "US",
  val tableName: String = "agent_events",
  val credentials: Credentials? = null,
) {

  /**
   * Fluent builder for [BigQueryLoggerConfig], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var projectId: String? = null
    private var datasetId: String? = null
    private var enabled: Boolean = true
    private var location: String = "US"
    private var tableName: String = "agent_events"
    private var credentials: Credentials? = null

    fun projectId(projectId: String): Builder = apply { this.projectId = projectId }

    fun datasetId(datasetId: String): Builder = apply { this.datasetId = datasetId }

    fun enabled(enabled: Boolean): Builder = apply { this.enabled = enabled }

    fun location(location: String): Builder = apply { this.location = location }

    fun tableName(tableName: String): Builder = apply { this.tableName = tableName }

    fun credentials(credentials: Credentials?): Builder = apply { this.credentials = credentials }

    fun build(): BigQueryLoggerConfig =
      BigQueryLoggerConfig(
        projectId = checkNotNull(projectId) { "BigQueryLoggerConfig.Builder requires projectId." },
        datasetId = checkNotNull(datasetId) { "BigQueryLoggerConfig.Builder requires datasetId." },
        enabled = enabled,
        location = location,
        tableName = tableName,
        credentials = credentials,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
