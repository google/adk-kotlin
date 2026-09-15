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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/** Emits a single content event flagged as the node's output, optionally carrying [output] too. */
private class MessageNode(
  override val name: String,
  private val message: Content,
  private val output: Any? = null,
) : Node {
  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
    emit(
      Event(
        author = "",
        content = message,
        output = output,
        nodeInfo = NodeInfo(messageAsOutput = true),
      )
    )
  }
}

/** Throws whatever [error] builds, so the runner's failure path can be observed. */
private class ThrowingNode(override val name: String, private val error: () -> Throwable) : Node {
  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow { throw error() }
}

class NodeRunnerTest {

  /** Runs [node] under a root parent whose branch is [parentBranch], collecting emitted events. */
  private fun runNode(
    node: Node,
    parentBranch: String?,
    useSubBranch: Boolean = false,
    overrideBranch: String? = null,
  ): Pair<NodeContext, List<Event>> = runBlocking {
    val events = mutableListOf<Event>()
    val invocationContext = testInvocationContext(branch = parentBranch)
    val root =
      NodeContext(
        invocationContext = invocationContext,
        node = StubNode("root"),
        eventSink = { events.add(it) },
        nodePath = "",
      )
    val context =
      NodeRunner(
          node = node,
          parent = root,
          runId = "1",
          useSubBranch = useSubBranch,
          overrideBranch = overrideBranch,
        )
        .run(nodeInput = null)
    context to events.toList()
  }

  @Test
  fun aFannedOutNodeRunsOnASubBranchDerivedFromItsParentBranch() {
    // Act
    val (context, events) =
      runNode(Emitter("greet", "hi"), parentBranch = "root", useSubBranch = true)

    // Assert: the branch descends from the parent's, named for the node and its run id.
    assertEquals("root.greet@1", context.invocationContext.branch)
    assertEquals("root.greet@1", events.single().branch)
  }

  @Test
  fun anOverrideBranchReplacesTheInheritedBranch() {
    // Act: a join re-merge or single-successor inheritance arrives as an override branch.
    val (context, events) =
      runNode(Emitter("greet", "hi"), parentBranch = "root", overrideBranch = "merged")

    // Assert
    assertEquals("merged", context.invocationContext.branch)
    assertEquals("merged", events.single().branch)
  }

  @Test
  fun withNoBranchDirectiveTheNodeInheritsItsParentBranch() {
    // Act
    val (context, events) = runNode(Emitter("greet", "hi"), parentBranch = "root")

    // Assert
    assertEquals("root", context.invocationContext.branch)
    assertEquals("root", events.single().branch)
  }

  @Test
  fun aMessageAsOutputEventCapturesItsContentAsTheNodeOutput() {
    // Arrange
    val message = Content(parts = listOf(Part(text = "hi")))
    val node = MessageNode("greet", message)

    // Act
    val (context, events) = runNode(node, parentBranch = "root")

    // Assert: the content becomes the output, and only the content event is emitted (no duplicate).
    assertEquals(message, context.output)
    assertEquals(1, events.size)
    assertEquals(message, events.single().content)
    assertNull(events.single().output)
  }

  @Test
  fun aMessageAsOutputEventThatAlsoCarriesOutputIsEmittedOnce() {
    // Arrange
    val message = Content(parts = listOf(Part(text = "hi")))
    val node = MessageNode("greet", message, output = "hi")

    // Act
    val (_, events) = runNode(node, parentBranch = "root")

    // Assert: the content event delivers the output; the deferred output event is suppressed.
    assertEquals(1, events.size)
    assertEquals(message, events.single().content)
    assertNull(events.single().output)
  }

  @Test
  fun everyEmittedEventIsStampedWithTheNodePathAndInvocationId() {
    // Act
    val (_, events) = runNode(Emitter("greet", "hi"), parentBranch = "root")

    // Assert: the path roots at the node under the empty-path parent, and the id is the run's.
    val event = events.single()
    assertEquals("greet@1", event.nodeInfo?.path)
    assertEquals("test-invocation-id", event.invocationId)
    assertEquals("hi", event.output)
  }

  @Test
  fun aFailureReportsTheDeclaredCrossImplementationTypeName() {
    // Arrange: the declared type name is what another ADK implementation matches on.
    val node = ThrowingNode("boom") { NodeExecutionException("RuntimeError", "node failed") }

    // Act
    val (context, events) = runNode(node, parentBranch = "root")

    // Assert
    assertEquals("RuntimeError", events.single().errorCode)
    assertEquals("node failed", events.single().errorMessage)
    assertEquals("boom@1", context.failure?.nodePath)
  }

  @Test
  fun aFailureWithoutADeclaredTypeNameReportsTheExceptionClassName() {
    // Arrange
    val node = ThrowingNode("boom") { IllegalStateException("node failed") }

    // Act
    val (_, events) = runNode(node, parentBranch = "root")

    // Assert
    assertEquals("IllegalStateException", events.single().errorCode)
  }
}
