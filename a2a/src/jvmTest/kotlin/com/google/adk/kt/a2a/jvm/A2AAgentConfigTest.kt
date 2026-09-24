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

package com.google.adk.kt.a2a.jvm

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.testing.DummyAgent
import com.google.common.truth.Truth.assertThat
import org.a2aproject.sdk.client.http.JdkA2AHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class A2AAgentConfigTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val config =
      A2AAgentConfig(
        httpClient = JdkA2AHttpClient(),
        description = "Remote helper",
        subAgents = listOf(DummyAgent(name = "sub")),
        beforeAgentCallbacks =
          listOf(BeforeAgentCallback { CallbackChoice.Continue(EventActions()) }),
        afterAgentCallbacks = listOf(AfterAgentCallback { CallbackChoice.Continue(Unit) }),
      )

    assertThat(config.toBuilder().build()).isEqualTo(config.copy())
    assertThat(config.toBuilder().description(null).build())
      .isEqualTo(config.copy(description = null))
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_unsetHttpClient_defaultsToJdkClient() {
    assertThat(A2AAgentConfig.builder().build().httpClient)
      .isInstanceOf(JdkA2AHttpClient::class.java)
  }
}
