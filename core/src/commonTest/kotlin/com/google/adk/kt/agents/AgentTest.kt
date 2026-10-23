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

package com.google.adk.kt.agents

import com.google.adk.kt.testing.DummyAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTest {

  @Test
  fun findAgent_existingAgent_returnsAgent() {
    val subAgent2 = DummyAgent("sub-2")
    val subAgent1 = DummyAgent("sub-1", listOf(subAgent2))
    val rootAgent = DummyAgent("root", listOf(subAgent1))

    assertEquals(rootAgent, rootAgent.findAgent("root"))
    assertEquals(subAgent1, rootAgent.findAgent("sub-1"))
    assertEquals(subAgent2, rootAgent.findAgent("sub-2"))
    assertNull(rootAgent.findAgent("non-existent"))
  }
}
