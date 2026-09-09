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

import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BaseRetrievalToolTest {

  private class TestRetrievalTool :
    BaseRetrievalTool(name = "test_retrieval", description = "A test retrieval tool.") {
    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
      emptyList<String>()
  }

  @Test
  fun declaration_exposesQueryStringParameter() {
    val declaration = TestRetrievalTool().declaration()

    assertEquals("test_retrieval", declaration.name)
    assertEquals("A test retrieval tool.", declaration.description)
    assertEquals(Type.OBJECT, declaration.parameters?.type)
    val query = declaration.parameters?.properties?.get("query")
    assertEquals(Type.STRING, query?.type)
    assertEquals("The query to retrieve.", query?.description)
  }

  @Test
  fun declaration_omitsRequiredForParityWithJavaAndPython() {
    // Java/Python ADK BaseRetrievalTool do not mark `query` as required; match that here.
    assertNull(TestRetrievalTool().declaration().parameters?.required)
  }
}
