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

package com.google.adk.kt.memory.appsearch

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumented tests for [AppSearchMemoryService], exercising the full stack (service
 * plus the real AppSearch LocalStorage engine) on a device or emulator. The service logic is
 * covered on the host by [AppSearchMemoryServiceTest] (against a fake index); these instrumented
 * tests add end-to-end confidence against the real engine on real Android.
 *
 * Each test uses a unique `(appName, userId)` scope so runs are isolated even though LocalStorage
 * data persists on disk.
 */
@RunWith(AndroidJUnit4::class)
class AppSearchMemoryServiceInstrumentedTest {

  private lateinit var context: Context
  private lateinit var memoryService: AppSearchMemoryService
  private val appName = "app-${Uuid.random()}"

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    memoryService = AppSearchMemoryService.fromContext(context, databaseName = TEST_DB_NAME)
  }

  @After
  fun tearDown() {
    runCatching { memoryService.close() }
  }

  @Test
  fun addSessionToMemory_search_returnsMatchingText(): Unit = runBlocking {
    memoryService.addSessionToMemory(sessionWith("user-1", "The Berlin trip was wonderful"))

    val memories = memoryService.searchMemory(appName, "user-1", "berlin").memories

    assertThat(memories).hasSize(1)
    assertThat(memories[0].content.parts[0].text).isEqualTo("The Berlin trip was wonderful")
  }

  @Test
  fun addMemory_persistsAcrossServiceReopen(): Unit = runBlocking {
    memoryService.addMemory(
      appName,
      "user-1",
      listOf(MemoryEntry(content = contentOf("Remember the API key rotation"), id = "m-1")),
    )

    // Reopen the on-disk index with a fresh service instance to prove durability.
    memoryService.close()
    val reopened = AppSearchMemoryService.fromContext(context, databaseName = TEST_DB_NAME)
    try {
      val memories = reopened.searchMemory(appName, "user-1", "rotation").memories
      assertThat(memories).hasSize(1)
      assertThat(memories[0].id).isEqualTo("m-1")
    } finally {
      reopened.close()
    }
  }

  @Test
  fun searchMemory_otherUser_returnsEmpty(): Unit = runBlocking {
    memoryService.addSessionToMemory(sessionWith("user-1", "The Berlin trip was wonderful"))

    assertThat(memoryService.searchMemory(appName, "user-2", "berlin").memories).isEmpty()
  }

  private fun contentOf(text: String): Content =
    Content(role = Role.USER, parts = listOf(Part(text = text)))

  private fun sessionWith(userId: String, text: String): Session =
    Session(
      key = SessionKey(appName, userId, "session-1"),
      state = State(),
      events = mutableListOf(Event(author = Role.USER, content = contentOf(text), timestamp = 0L)),
      lastUpdateTime = Clock.System.now(),
    )

  private companion object {
    const val TEST_DB_NAME = "adk_memory_instrumented_test"
  }
}
