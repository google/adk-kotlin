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

package com.google.adk.kt.types

import com.google.genai.kotlin.types.FunctionDeclaration as GenAiFunctionDeclaration
import com.google.genai.kotlin.types.Schema as GenAiSchema
import com.google.genai.kotlin.types.Type as GenAiType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FunctionDeclarationTest {

  @Test
  fun toGenaiSdk_populatesSdkObject() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("param1" to Schema(type = Type.STRING)))
    val functionDeclaration =
      FunctionDeclaration(
        name = "myFunction",
        description = "This is a simple function",
        parameters = schema,
      )

    val sdkObject = functionDeclaration.toGenaiSdk()
    assertNotNull(sdkObject)
    assertEquals("myFunction", sdkObject.name)
    assertEquals("This is a simple function", sdkObject.description)
    assertEquals(GenAiType.OBJECT, sdkObject.parameters?.type)
  }

  @Test
  fun fromGenaiSdk_readsFromSdkObject() {
    val genAiFunctionDeclaration =
      GenAiFunctionDeclaration(
        name = "testFunction",
        description = "A test description",
        parameters = GenAiSchema(type = GenAiType.STRING),
      )

    val functionDeclaration = genAiFunctionDeclaration.fromGenaiSdk()

    assertEquals("testFunction", functionDeclaration.name)
    assertEquals("A test description", functionDeclaration.description)
    val parameters = functionDeclaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.STRING, parameters.type)
  }
}
