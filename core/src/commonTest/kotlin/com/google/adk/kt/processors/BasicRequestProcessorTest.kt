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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.GenerateContentConfig
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BasicRequestProcessorTest {

  @Test
  fun run_withAgentFields_setsModelAndConfigOnRequest() = runTest {
    val model = DummyModel("gemini")
    val config = GenerateContentConfig()
    val agent = LlmAgent(name = "test", model = model, generateContentConfig = config)
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = BasicRequestProcessor()
    request = processor.process(context, request)

    assertEquals(model, request.model)
    assertEquals(config, request.config)
  }
}
