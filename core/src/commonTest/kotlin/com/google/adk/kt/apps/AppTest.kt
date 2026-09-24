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

@file:OptIn(com.google.adk.kt.annotations.ExperimentalContextCachingFeature::class)

package com.google.adk.kt.apps

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppTest {

  @Test
  fun construct_validNameAndAgent_exposesProperties() {
    val agent = DummyAgent(name = "root")

    val app = App(appName = "my_app", rootAgent = agent)

    assertEquals("my_app", app.appName)
    assertSame(agent, app.rootAgent)
  }

  @Test
  fun construct_noEventsCompactionConfig_defaultsToNull() {
    val app = App(appName = "my_app", rootAgent = DummyAgent())

    assertNull(app.eventsCompactionConfig)
  }

  @Test
  fun construct_withEventsCompactionConfig_exposesIt() {
    val config = EventsCompactionConfig(compactionInterval = 2, overlapSize = 1)

    val app = App(appName = "my_app", rootAgent = DummyAgent(), eventsCompactionConfig = config)

    assertSame(config, app.eventsCompactionConfig)
  }

  @Test
  fun construct_noContextCacheConfig_defaultsToNull() {
    val app = App(appName = "my_app", rootAgent = DummyAgent())

    assertNull(app.contextCacheConfig)
  }

  @Test
  fun construct_withContextCacheConfig_exposesIt() {
    val config = ContextCacheConfig(cacheIntervals = 5)

    val app = App(appName = "my_app", rootAgent = DummyAgent(), contextCacheConfig = config)

    assertSame(config, app.contextCacheConfig)
  }

  @Test
  fun construct_emptyName_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { App(appName = "", rootAgent = DummyAgent()) }
  }

  @Test
  fun construct_nameStartingWithDigit_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { App(appName = "1app", rootAgent = DummyAgent()) }
  }

  @Test
  fun construct_nameStartingWithUnderscore_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { App(appName = "_app", rootAgent = DummyAgent()) }
  }

  @Test
  fun construct_nameWithHyphen_isAccepted() {
    val app = App(appName = "my-app", rootAgent = DummyAgent())

    assertEquals("my-app", app.appName)
  }

  @Test
  fun construct_nameWithLetterFollowedByDigit_isAccepted() {
    val app = App(appName = "a1", rootAgent = DummyAgent())

    assertEquals("a1", app.appName)
  }

  @Test
  fun construct_nameWithSpace_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { App(appName = "my app", rootAgent = DummyAgent()) }
  }

  @Test
  fun construct_reservedNameUser_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { App(appName = "user", rootAgent = DummyAgent()) }
  }

  @Test
  fun construct_default_hasEmptyPluginsAndNullResumability() {
    val app = App(appName = "my_app", rootAgent = DummyAgent(name = "root"))

    assertEquals(emptyList(), app.plugins)
    assertNull(app.resumabilityConfig)
  }

  @Test
  fun construct_withPluginsAndResumability_exposesProperties() {
    val plugin =
      object : Plugin {
        override val name = "test-plugin"
      }
    val resumability = ResumabilityConfig(isResumable = true)

    val app =
      App(
        appName = "my_app",
        rootAgent = DummyAgent(name = "root"),
        plugins = listOf(plugin),
        resumabilityConfig = resumability,
      )

    assertEquals(listOf(plugin), app.plugins)
    assertSame(resumability, app.resumabilityConfig)
  }

  @Test
  fun construct_positionalArguments_keepTheReleasedParameterOrder() {
    // Arrange
    val agent = DummyAgent(name = "root")
    val plugin =
      object : Plugin {
        override val name = "positional-plugin"
      }
    val resumability = ResumabilityConfig(isResumable = true)

    // Act: positional calls written against the released API, where `plugins` is third.
    val app = App("my_app", agent, listOf(plugin), resumability)
    val (appName, rootAgent, plugins, resumabilityConfig) = app
    val copied = app.copy("other_app", agent, emptyList())

    // Assert
    assertEquals("my_app", appName)
    assertSame(agent, rootAgent)
    assertEquals(listOf(plugin), plugins)
    assertSame(resumability, resumabilityConfig)
    assertNull(app.rootNode)
    assertEquals("other_app", copied.appName)
    assertEquals(emptyList(), copied.plugins)
    assertSame(resumability, copied.resumabilityConfig)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val app =
      App(
        appName = "my_app",
        rootAgent = DummyAgent(name = "root"),
        rootNode = DummyAgent(name = "node"),
        plugins =
          listOf(
            object : Plugin {
              override val name = "test-plugin"
            }
          ),
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        eventsCompactionConfig = EventsCompactionConfig(compactionInterval = 2, overlapSize = 1),
        contextCacheConfig = ContextCacheConfig(cacheIntervals = 5),
      )

    assertEquals(app.copy(), app.toBuilder().build())
    assertEquals(app.copy(appName = "other_app"), app.toBuilder().appName("other_app").build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_nodeRootedApp_matchesCopy() {
    val app = App(appName = "my_app", rootNode = DummyAgent(name = "node"))

    assertEquals(app.copy(), app.toBuilder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_switchingNodeRootToAgent_matchesCopy() {
    val app = App(appName = "my_app", rootNode = DummyAgent(name = "node"))
    val agent = DummyAgent(name = "agent")

    val rebuilt = app.toBuilder().rootNode(null).rootAgent(agent).build()

    assertEquals(app.copy(rootAgent = agent, rootNode = null), rebuilt)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_switchingToAnotherNode_matchesCopy() {
    val app = App(appName = "my_app", rootNode = DummyAgent(name = "first"))
    val second = DummyAgent(name = "second")

    assertEquals(app.copy(rootNode = second), app.toBuilder().rootNode(second).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_rootAgentFromPreviousNode_matchesCopy() {
    val app =
      App(appName = "my_app", rootNode = DummyAgent(name = "first"))
        .copy(rootNode = DummyAgent(name = "second"))

    assertEquals(app.copy(), app.toBuilder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_reRootingOnAnotherNode_derivesItsRootAgent() {
    val app = App(appName = "my_app", rootNode = DummyAgent(name = "first"))
    val second = DummyAgent(name = "second")

    val rebuilt = app.toBuilder().rootAgent(null).rootNode(second).build()

    assertSame(second, rebuilt.rootNode)
    assertEquals("second", rebuilt.rootAgent.name)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_clearingNodeRootWithoutAgent_throwsLikeCopy() {
    val app = App(appName = "my_app", rootNode = DummyAgent(name = "node"))

    assertFailsWith<IllegalArgumentException> { app.copy(rootNode = null) }
    assertFailsWith<IllegalArgumentException> { app.toBuilder().rootNode(null).build() }
  }
}
