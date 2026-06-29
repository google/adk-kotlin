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

import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyMemoryService
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackContextTest {
  @Test
  fun state_mergesSessionStateAndEventActionsDelta() = runBlocking {
    val session =
      Session(
        key = SessionKey(appName = "app-name", userId = "user-id", id = "session-id"),
        state =
          State(
            concurrentMutableMapOf<String, Any>().apply {
              putAll(mapOf("key1" to "val1", "key2" to "val2"))
            }
          ),
        events = mutableListOf(),
      )
    val context = testInvocationContext(session = session)
    val eventActions =
      EventActions(
        stateDelta =
          concurrentMutableMapOf<String, Any>().apply {
            putAll(mapOf("key2" to "newVal2", "key3" to "val3"))
          }
      )
    val callbackContext = context.toCallbackContext(eventActions)

    assertEquals(
      mapOf("key1" to "val1", "key2" to "newVal2", "key3" to "val3"),
      callbackContext.state,
    )
  }

  @Test
  fun state_filtersOutRemovedState() = runBlocking {
    val session =
      Session(
        key = SessionKey(appName = "app-name", userId = "user-id", id = "session-id"),
        state =
          State(
            concurrentMutableMapOf<String, Any>().apply {
              putAll(mapOf("key1" to "val1", "key2" to "val2"))
            }
          ),
        events = mutableListOf(),
      )
    val context = testInvocationContext(session = session)
    val eventActions =
      EventActions(
        stateDelta = concurrentMutableMapOf<String, Any>().apply { put("key1", State.REMOVED) }
      )
    val callbackContext = context.toCallbackContext(eventActions)

    assertEquals(mapOf("key2" to "val2"), callbackContext.state)
  }

  @Test
  fun updateState_modifiesEventActionsDelta() = runBlocking {
    val session =
      Session(
        key = SessionKey(appName = "app-name", userId = "user-id", id = "session-id"),
        state =
          State(concurrentMutableMapOf<String, Any>().apply { putAll(mapOf("key1" to "val1")) }),
        events = mutableListOf(),
      )
    val context = testInvocationContext(session = session)
    val callbackContext = context.toCallbackContext()

    callbackContext.updateState("key2", "val2")
    callbackContext.updateState("key1", "newVal1")

    // updateState also allows removing values
    callbackContext.updateState("key3", State.REMOVED)

    assertEquals("newVal1", callbackContext.state["key1"])
    assertEquals("val2", callbackContext.state["key2"])
    assertNull(callbackContext.state["key3"])

    assertEquals("newVal1", callbackContext.eventActions.stateDelta["key1"])
    assertEquals("val2", callbackContext.eventActions.stateDelta["key2"])
    assertEquals(State.REMOVED, callbackContext.eventActions.stateDelta["key3"])
  }

  @Test
  fun addSessionToMemory_throwsWhenServiceNotAvailable() = runBlocking {
    val session = testSession()
    val context = testInvocationContext(session = session)
    val callbackContext = context.toCallbackContext()

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking { callbackContext.addSessionToMemory() }
      }
    assertTrue(exception.message!!.contains("memory service is not available"))
  }

  @Test
  fun addSessionToMemory_callsMemoryService() = runBlocking {
    val session = testSession()
    val memoryService = DummyMemoryService()
    val context = testInvocationContext(session = session, memoryService = memoryService)
    val callbackContext = context.toCallbackContext()

    callbackContext.addSessionToMemory()

    assertEquals(1, memoryService.addedSessions.size)
    assertEquals(session, memoryService.addedSessions[0])
  }

  @Test
  fun endInvocation_setsIsEndOfInvocationOnContext() = runBlocking {
    // Callbacks that live outside the `com.google.adk.kt` module cannot reach
    // `CallbackContext.invocationContext` directly (it is `internal`). The public
    // `endInvocation()` helper is the supported way for them to terminate the invocation,
    // mirroring Python ADK's `callback_context._invocation_context.end_invocation = True` and
    // Java ADK's `EventActions.setEndInvocation(true)`.
    val context = testInvocationContext(session = testSession())
    val callbackContext = context.toCallbackContext()

    assertFalse(context.isEndOfInvocation)

    callbackContext.endInvocation()

    assertTrue(context.isEndOfInvocation)
  }

  @Test
  fun callbackContext_creation_setsDefaultValues() = runBlocking {
    val context =
      testInvocationContext(userContent = Content(role = "user"), invocationId = "invocation-id")
    val callbackContext = context.toCallbackContext()

    callbackContext.updateState("key", "value")
    assertEquals("value", callbackContext.state["key"])
    assertNotNull(callbackContext.eventActions.stateDelta["key"])
  }

  @Test
  fun saveAndLoadArtifact_roundTrips_andRecordsDelta() = runBlocking {
    val callbackContext =
      testInvocationContext(artifactService = InMemoryArtifactService()).toCallbackContext()

    val unused = callbackContext.saveArtifact("a", Part(text = "hello"))

    assertEquals(Part(text = "hello"), callbackContext.loadArtifact("a"))
  }

  @Test
  fun listArtifacts_returnsSavedKeys() = runBlocking {
    val callbackContext =
      testInvocationContext(artifactService = InMemoryArtifactService()).toCallbackContext()
    val unused1 = callbackContext.saveArtifact("k1", Part(text = "v1"))
    val unused2 = callbackContext.saveArtifact("k2", Part(text = "v2"))

    assertEquals(listOf("k1", "k2"), callbackContext.listArtifacts().sorted())
  }

  @Test
  fun loadArtifact_returnsNullWhenNoArtifactService() = runBlocking {
    val context = testInvocationContext(artifactService = null)
    val callbackContext = context.toCallbackContext()

    assertNull(callbackContext.loadArtifact("missing"))
  }

  @Test
  fun listArtifacts_returnsEmptyWhenNoArtifactService() = runBlocking {
    val context = testInvocationContext(artifactService = null)
    val callbackContext = context.toCallbackContext()

    assertTrue(callbackContext.listArtifacts().isEmpty())
  }

  @Test
  fun saveArtifact_throwsWhenNoArtifactService() = runBlocking {
    val context = testInvocationContext(artifactService = null)
    val callbackContext = context.toCallbackContext()

    val ex =
      assertThrows(IllegalStateException::class.java) {
        runBlocking { callbackContext.saveArtifact("x", Part(text = "y")) }
      }
    assertTrue(ex.message!!.contains("artifactService not configured"))
  }
}
