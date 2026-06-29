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

package com.google.adk.kt.tools

import com.google.adk.kt.testing.DummyModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleSearchAgentToolTest {

  @Test
  fun createGoogleSearchAgent_buildsAgentWithGoogleSearchTool() {
    val agent = createGoogleSearchAgent(DummyModel("test"))

    assertEquals(GOOGLE_SEARCH_AGENT_NAME, agent.name)
    assertEquals(1, agent.tools.size)
    assertIs<GoogleSearchTool>(agent.tools[0])
  }

  @Test
  fun googleSearchAgentTool_declaration_usesAgentName() {
    val tool = GoogleSearchAgentTool(createGoogleSearchAgent(DummyModel("test")))

    assertEquals(GOOGLE_SEARCH_AGENT_NAME, tool.declaration().name)
    assertTrue(tool.propagateGroundingMetadata)
  }
}
