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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class InstructionStateInjectorTest {

  private fun createFakeContext(
    session: Session? = null,
    artifactService: ArtifactService? = null,
  ): CallbackContext {
    val fakeSession = session ?: testSession()
    val fakeAgent =
      object : BaseAgent(name = "fake-agent") {
        override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
      }
    return InvocationContext(
        session = fakeSession,
        runConfig = null,
        agent = fakeAgent,
        artifactService = artifactService,
      )
      .toCallbackContext()
  }

  @Test
  fun injectSessionState_nullTemplate_returnsEmptyString() = runTest {
    val context = createFakeContext()

    val result = InstructionStateInjector.injectSessionState(context = context, template = null)

    assertThat(result).isEmpty()
  }

  @Test
  fun injectSessionState_emptyTemplate_returnsEmptyString() = runTest {
    val context = createFakeContext()

    val result = InstructionStateInjector.injectSessionState(context = context, template = "")

    assertThat(result).isEmpty()
  }

  @Test
  fun injectSessionState_noPlaceholders_returnsOriginalTemplate() = runTest {
    val context = createFakeContext()
    val template = "This is a simple template without placeholders."

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals(template, result)
  }

  @Test
  fun injectSessionState_withValidStateVariable_replacesWithStateValue() = runTest {
    val context =
      createFakeContext(
        session =
          Session(
            key = SessionKey(appName = "app", userId = "user", id = "sess"),
            state = State().apply { this["user_name"] = "Alice" },
          )
      )
    val template = "Hello {user_name}, welcome back!"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals("Hello Alice, welcome back!", result)
  }

  @Test
  fun injectSessionState_withMissingStateVariable_throwsException() = runTest {
    val context = createFakeContext(session = testSession())
    val template = "Hello {missing_var}!"

    val exception =
      assertFailsWith<IllegalArgumentException> {
        InstructionStateInjector.injectSessionState(context = context, template = template)
      }

    assertEquals("Context variable not found: `missing_var`.", exception.message)
  }

  @Test
  fun injectSessionState_withOptionalMissingStateVariable_replacesWithEmptyString() = runTest {
    val context = createFakeContext(session = testSession())
    val template = "Hello {missing_var?}!"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals("Hello !", result)
  }

  @Test
  fun injectSessionState_withOptionalPresentStateVariable_replacesWithStateValue() = runTest {
    val context =
      createFakeContext(
        session =
          Session(
            key = SessionKey(appName = "app", userId = "user", id = "sess"),
            state = State().apply { this["user_name"] = "Alice" },
          )
      )
    val template = "Hello {user_name?}!"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals("Hello Alice!", result)
  }

  @Test
  fun injectSessionState_withInvalidStateVariableName_leavesPlaceholderUntouched() = runTest {
    val context = createFakeContext(session = testSession())
    val template = "Here is some JSON: { \"key\": \"value\" }"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals(template, result)
  }

  @Test
  fun injectSessionState_withValidArtifact_replacesWithArtifactJson() = runTest {
    val mockArtifactPart = Part(text = "Artifact content")
    val sessionKey = SessionKey(appName = "app", userId = "user", id = "sess1")
    val mockArtifactService =
      mock<ArtifactService> {
        onBlocking { loadArtifact(sessionKey, "my_doc") } doReturn mockArtifactPart
      }
    val context =
      createFakeContext(session = Session(key = sessionKey), artifactService = mockArtifactService)
    val template = "Read this document: {artifact.my_doc}"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    val expectedJson = Json.toJsonString(mockArtifactPart)
    assertEquals("Read this document: $expectedJson", result)
  }

  @Test
  fun injectSessionState_withMissingArtifact_throwsException() = runTest {
    val mockArtifactService =
      mock<ArtifactService> { onBlocking { loadArtifact(any(), any(), any()) } doReturn null }
    val context = createFakeContext(session = testSession(), artifactService = mockArtifactService)
    val template = "Read this document: {artifact.missing_doc}"

    val exception =
      assertFailsWith<IllegalArgumentException> {
        InstructionStateInjector.injectSessionState(context = context, template = template)
      }

    assertEquals("Artifact missing_doc not found.", exception.message)
  }

  @Test
  fun injectSessionState_withOptionalMissingArtifact_replacesWithEmptyString() = runTest {
    val context =
      createFakeContext(
        session = testSession(),
        artifactService =
          mock<ArtifactService> { onBlocking { loadArtifact(any(), any(), any()) } doReturn null },
      )
    val template = "Read this document: {artifact.missing_doc?}"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals("Read this document: ", result)
  }

  @Test
  fun injectSessionState_withOptionalPresentArtifact_replacesWithArtifactJson() = runTest {
    val mockArtifactPart = Part(text = "Artifact content")
    val sessionKey = SessionKey(appName = "app", userId = "user", id = "sess1")
    val context =
      createFakeContext(
        session = Session(key = sessionKey, state = State()),
        artifactService =
          mock<ArtifactService> {
            onBlocking { loadArtifact(sessionKey, "my_doc") } doReturn mockArtifactPart
          },
      )
    val template = "Read this document: {artifact.my_doc?}"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    val expectedJson = Json.toJsonString(mockArtifactPart)
    assertEquals("Read this document: $expectedJson", result)
  }

  @Test
  fun injectSessionState_withValidPrefix_replacesWithStateValue() = runTest {
    val context =
      createFakeContext(
        session =
          Session(
            key = SessionKey(appName = "app", userId = "user", id = "sess"),
            state = State().apply { this["app:app_version"] = "1.0.0" },
          )
      )
    val template = "App version is {app:app_version}"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals("App version is 1.0.0", result)
  }

  @Test
  fun injectSessionState_withInvalidPrefix_leavesPlaceholderUntouched() = runTest {
    val context = createFakeContext(session = testSession())
    val template = "Unknown prefix: {unknown:variable}"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    assertEquals("Unknown prefix: {unknown:variable}", result)
  }

  @Test
  fun injectSessionState_multipleVariablesAndArtifacts() = runTest {
    val mockArtifactPart = Part(text = "Report content")
    val sessionKey = SessionKey(appName = "app", userId = "usr", id = "sess")
    val context =
      createFakeContext(
        session =
          Session(
            key = sessionKey,
            state =
              State().apply {
                this["name"] = "Bob"
                this["app:version"] = "2.0"
              },
          ),
        artifactService =
          mock<ArtifactService> {
            onBlocking { loadArtifact(sessionKey, "report") } doReturn mockArtifactPart
          },
      )
    val template = "Hello {name}, running v{app:version}. Here is the report: {artifact.report}"

    val result = InstructionStateInjector.injectSessionState(context = context, template = template)

    val expectedJson = Json.toJsonString(mockArtifactPart)
    assertEquals("Hello Bob, running v2.0. Here is the report: $expectedJson", result)
  }
}
