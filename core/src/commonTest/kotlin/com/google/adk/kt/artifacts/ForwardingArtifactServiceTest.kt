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

package com.google.adk.kt.artifacts

import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ForwardingArtifactServiceTest {

  private val unusedChildSessionKey = SessionKey("ignored_app", "ignored_user", "ignored_session")

  @Test
  fun saveArtifact_writesToParentServiceAndRecordsDelta() = runTest {
    val parentService = InMemoryArtifactService()
    val toolContext = testToolContext(testInvocationContext(artifactService = parentService))
    val forwarder = ForwardingArtifactService(toolContext)
    val artifact = Part(text = "bytes-1")

    forwarder.saveArtifact(unusedChildSessionKey, "a", artifact)

    // The parent service stores under the parent's session key, not the incoming child key.
    assertEquals(
      artifact,
      parentService.loadArtifact(toolContext.invocationContext.session.key, "a"),
    )
    // Nothing was written under the child key the caller passed in.
    assertNull(parentService.loadArtifact(unusedChildSessionKey, "a"))
  }

  @Test
  fun loadArtifact_readsFromParentServiceIgnoringIncomingSessionKey() = runTest {
    val parentService = InMemoryArtifactService()
    val toolContext = testToolContext(testInvocationContext(artifactService = parentService))
    val artifact = Part(text = "bytes-2")
    val unusedVersion =
      parentService.saveArtifact(toolContext.invocationContext.session.key, "b", artifact)
    val forwarder = ForwardingArtifactService(toolContext)

    val loaded = forwarder.loadArtifact(unusedChildSessionKey, "b")

    assertEquals(artifact, loaded)
  }

  @Test
  fun listArtifactKeys_returnsParentSessionsKeys() = runTest {
    val parentService = InMemoryArtifactService()
    val toolContext = testToolContext(testInvocationContext(artifactService = parentService))
    val unused1 =
      parentService.saveArtifact(
        toolContext.invocationContext.session.key,
        "alpha",
        Part(text = "1"),
      )
    val unused2 =
      parentService.saveArtifact(
        toolContext.invocationContext.session.key,
        "beta",
        Part(text = "2"),
      )
    val forwarder = ForwardingArtifactService(toolContext)

    val keys = forwarder.listArtifactKeys(unusedChildSessionKey).sorted()

    assertEquals(listOf("alpha", "beta"), keys)
  }

  @Test
  fun deleteArtifact_deletesUnderParentSessionKey() = runTest {
    val parentService = InMemoryArtifactService()
    val toolContext = testToolContext(testInvocationContext(artifactService = parentService))
    val unused =
      parentService.saveArtifact(
        toolContext.invocationContext.session.key,
        "to-delete",
        Part(text = "x"),
      )
    val forwarder = ForwardingArtifactService(toolContext)

    forwarder.deleteArtifact(unusedChildSessionKey, "to-delete")

    assertNull(parentService.loadArtifact(toolContext.invocationContext.session.key, "to-delete"))
  }

  @Test
  fun listVersions_returnsParentSessionsVersions() = runTest {
    val parentService = InMemoryArtifactService()
    val toolContext = testToolContext(testInvocationContext(artifactService = parentService))
    val unused1 =
      parentService.saveArtifact(toolContext.invocationContext.session.key, "v", Part(text = "v0"))
    val unused2 =
      parentService.saveArtifact(toolContext.invocationContext.session.key, "v", Part(text = "v1"))
    val forwarder = ForwardingArtifactService(toolContext)

    val versions = forwarder.listVersions(unusedChildSessionKey, "v")

    assertEquals(listOf(0, 1), versions)
  }

  @Test
  fun saveAndReloadArtifact_returnsTheSavedArtifact() = runTest {
    val toolContext = testToolContext(testInvocationContext(artifactService = InMemoryArtifactService()))
    val artifact = Part(text = "round-trip")
    val forwarder = ForwardingArtifactService(toolContext)

    val reloaded = forwarder.saveAndReloadArtifact(unusedChildSessionKey, "rt", artifact)

    assertEquals(artifact, reloaded)
    assertEquals(0, toolContext.actions.artifactDelta["rt"])
  }

  @Test
  fun saveArtifact_throwsWhenParentInvocationHasNoArtifactService() = runTest {
    val toolContext = testToolContext(testInvocationContext(artifactService = null))
    val forwarder = ForwardingArtifactService(toolContext)

    assertFailsWith<IllegalStateException> {
      forwarder.saveArtifact(unusedChildSessionKey, "x", Part(text = "y"))
    }
  }

  @Test
  fun deleteArtifact_throwsWhenParentInvocationHasNoArtifactService() = runTest {
    val toolContext = testToolContext(testInvocationContext(artifactService = null))
    val forwarder = ForwardingArtifactService(toolContext)

    assertFailsWith<IllegalStateException> { forwarder.deleteArtifact(unusedChildSessionKey, "x") }
  }

  @Test
  fun listVersions_throwsWhenParentInvocationHasNoArtifactService() = runTest {
    val toolContext = testToolContext(testInvocationContext(artifactService = null))
    val forwarder = ForwardingArtifactService(toolContext)

    assertFailsWith<IllegalStateException> { forwarder.listVersions(unusedChildSessionKey, "x") }
  }
}
