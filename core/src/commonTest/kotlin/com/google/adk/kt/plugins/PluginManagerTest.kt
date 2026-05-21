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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PluginManagerTest {

  private fun mockPlugin(name: String): Plugin {
    val plugin = mock<Plugin>()
    whenever(plugin.name).doReturn(name)
    return plugin
  }

  @Test
  fun ctor_duplicatePluginNames_throwsException() {
    val plugin1 = mockPlugin("plugin1")
    val plugin2 = mockPlugin("plugin1")
    val exception =
      assertFailsWith<IllegalArgumentException> { PluginManager(listOf(plugin1, plugin2)) }
    assertEquals("Duplicate plugin names found: 'plugin1'.", exception.message)
  }

  @Test
  fun getPlugin_notFound() {
    val pluginManager = PluginManager()
    assertNull(pluginManager.getPlugin("nonexistent"))
  }

  @Test
  fun initialization_precomputesCallbacks() = runTest {
    var called = false
    val plugin =
      object : Plugin {
        override val name = "test"

        override suspend fun onUserMessage(
          invocationContext: InvocationContext,
          userMessage: Content,
        ): Content {
          called = true
          return userMessage
        }
      }

    val manager = PluginManager(listOf(plugin))

    assertEquals(1, manager.onUserMessageCallbacks.size)
    val userMessage = Content(Role.USER)
    val invocationContext =
      InvocationContext(session = testSession(), runConfig = null, agent = DummyAgent("t"))
    val result = manager.onUserMessageCallbacks.first().call(invocationContext, userMessage)
    assertEquals(userMessage, result)
    assertTrue(called)
  }
}
