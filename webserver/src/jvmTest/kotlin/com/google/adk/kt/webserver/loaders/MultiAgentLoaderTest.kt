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

package com.google.adk.kt.webserver.loaders

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MultiAgentLoaderTest {

  @Test
  fun listAgents_returnsEveryNameSorted() {
    val loader = MultiAgentLoader(FakeAgent("beta"), FakeAgent("alpha"))

    assertThat(loader.listAgents()).containsExactly("alpha", "beta").inOrder()
  }

  @Test
  fun loadAgent_returnsTheAgentUnderItsOwnName() {
    val alpha = FakeAgent("alpha")
    val loader = MultiAgentLoader(alpha, FakeAgent("beta"))

    assertThat(loader.loadAgent("alpha")).isSameInstanceAs(alpha)
  }

  @Test
  fun loadAgent_returnsNullForAnUnknownName() {
    val loader = MultiAgentLoader(FakeAgent("alpha"))

    assertThat(loader.loadAgent("missing")).isNull()
  }

  @Test
  fun constructor_rejectsDuplicateNames() {
    assertThrows(IllegalArgumentException::class.java) {
      MultiAgentLoader(FakeAgent("dup"), FakeAgent("dup"))
    }
  }

  @Test
  fun emptyLoader_servesNoAgents() {
    val loader = MultiAgentLoader()

    assertThat(loader.listAgents()).isEmpty()
    assertThat(loader.loadAgent("anything")).isNull()
  }

  private class FakeAgent(name: String) : BaseAgent(name = name, description = "test agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
  }
}
