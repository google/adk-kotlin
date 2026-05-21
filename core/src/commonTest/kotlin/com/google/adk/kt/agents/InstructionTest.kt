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

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InstructionTest {

  private val context: ReadonlyContext = FakeReadonlyContext()

  @Test
  fun resolve_textInstruction_wrapsInSinglePartContent() = runTest {
    val instruction: Instruction = Instruction.Text("hello world")

    val resolved = instruction.resolve(context)

    assertEquals(1, resolved?.parts?.size)
    assertEquals("hello world", resolved?.parts?.firstOrNull()?.text)
  }

  @Test
  fun resolve_structuredInstruction_returnsExactContent() = runTest {
    val content = Content(parts = listOf(Part(text = "structured")))
    val instruction: Instruction = Instruction.Structured(content)

    val resolved = instruction.resolve(context)

    assertSame(content, resolved)
  }

  @Test
  fun resolve_providerReturningContent_returnsThatContent() = runTest {
    val produced = Content(parts = listOf(Part(text = "from provider")))
    val instruction: Instruction = Instruction.Provider { produced }

    val resolved = instruction.resolve(context)

    assertSame(produced, resolved)
  }

  @Test
  fun resolve_providerReturningNull_returnsNull() = runTest {
    val instruction: Instruction = Instruction.Provider { null }

    val resolved = instruction.resolve(context)

    assertNull(resolved)
  }

  @Test
  fun resolve_provider_receivesGivenContext() = runTest {
    var captured: ReadonlyContext? = null
    val instruction: Instruction = Instruction.Provider { ctx ->
      captured = ctx
      null
    }

    val unused = instruction.resolve(context)

    assertSame(context, captured)
  }

  @Test
  fun invoke_withString_buildsTextVariant() = runTest {
    val instruction = Instruction("hi")

    assertEquals(Instruction.Text("hi"), instruction)
  }

  @Test
  fun invoke_withContent_buildsStructuredVariant() = runTest {
    val content = Content(parts = listOf(Part(text = "hi")))

    val instruction = Instruction(content)

    assertEquals(Instruction.Structured(content), instruction)
  }

  @Test
  fun invoke_withLambda_buildsProviderVariantThatResolvesToProvidedContent() = runTest {
    val produced = Content(parts = listOf(Part(text = "from lambda")))

    val instruction = Instruction { produced }

    assertSame(produced, instruction.resolve(context))
  }
}

/** Minimal [ReadonlyContext] implementation for unit-testing [Instruction.resolve]. */
private class FakeReadonlyContext : ReadonlyContext {
  override val session: Session = testSession()
  override val runConfig: RunConfig? = null
  override val invocationId: String = "inv"
  override val agentName: String = "agent"
  override val state: Map<String, Any> = emptyMap()
  override val userId: String = "test_user_id"
  override val userContent: Content? = null
  override val branch: String? = null
  override val artifactService: ArtifactService? = null
  override val memoryService: MemoryService? = null

  override suspend fun getEvents(currentInvocation: Boolean, currentBranch: Boolean): List<Event> =
    emptyList()
}
