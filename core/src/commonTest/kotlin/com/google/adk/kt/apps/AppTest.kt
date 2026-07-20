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
  fun construct_nameWithHyphen_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { App(appName = "my-app", rootAgent = DummyAgent()) }
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
}
