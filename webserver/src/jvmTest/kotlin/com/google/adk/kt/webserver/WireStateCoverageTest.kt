/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.sessions.StateMapSerializer
import com.google.adk.kt.webserver.models.AgentRunRequest
import com.google.adk.kt.webserver.models.CreateSessionRequest
import com.google.adk.kt.webserver.models.SessionDto
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Holds every session state in the server's REST bodies to [StateMapSerializer], which types the
 * credentials ADK stores in state. A state field written with any other serializer reads them back
 * as plain values, so its output would hold a bare key or token in clear.
 */
@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class WireStateCoverageTest {

  /**
   * The bodies that carry session state: requests the routes decode, and the session they return.
   */
  private val roots: Map<String, KSerializer<*>> =
    mapOf(
      "AgentRunRequest" to AgentRunRequest.serializer(),
      "CreateSessionRequest" to CreateSessionRequest.serializer(),
      "SessionDto" to SessionDto.serializer(),
    )

  @Test
  fun stateFields_everyOne_usesTheStateSerializer() {
    assertThat(stateFields().filterValues { !it }.keys).isEmpty()
  }

  @Test
  fun stateFields_survey_reachesEachRootAndEventActions() {
    // The coverage assertion passes vacuously for any state field the survey stops short of.
    assertThat(stateFields().keys)
      .containsAtLeast(
        "AgentRunRequest.stateDelta",
        "CreateSessionRequest.state",
        "CreateSessionRequest.events[].actions.stateDelta",
        "SessionDto.state",
        "SessionDto.events[].actions.stateDelta",
      )
  }

  /** Each state field the survey reached, and whether [StateMapSerializer] writes it. */
  private fun stateFields(): Map<String, Boolean> =
    WireSchemaSurvey(roots)
      .types
      .flatMap { surveyed ->
        val type = surveyed.type
        (0 until type.elementsCount)
          // A map, so that a string field of the same name, such as OAuth2's state, isn't counted.
          .filter {
            type.getElementName(it) in STATE_FIELD_NAMES &&
              type.getElementDescriptor(it).kind == StructureKind.MAP
          }
          .map { index ->
            // Each state serializer shares this one descriptor, so identity tells them apart.
            val written = type.getElementDescriptor(index).nonNullOriginal
            "${surveyed.path}.${type.getElementName(index)}" to
              (written === StateMapSerializer.descriptor)
          }
      }
      .toMap()

  private companion object {
    // The names session state goes by on the wire, here and in every other ADK language.
    val STATE_FIELD_NAMES = setOf("state", "stateDelta")
  }
}
