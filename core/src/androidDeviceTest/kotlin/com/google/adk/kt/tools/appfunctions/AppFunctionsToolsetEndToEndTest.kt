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

@file:OptIn(ExperimentalAppFunctionsFeature::class)

package com.google.adk.kt.tools.appfunctions

import android.content.Context
import androidx.appfunctions.AppFunctionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [AppFunctionsToolset] against the real platform, using the app functions this test APK
 * declares about itself.
 *
 * Unit tests substitute the platform behind a seam, so nothing else proves that a real
 * `AppFunctionManager` discovers a real function and that a call reaches the app and comes back.
 * This needs a device or emulator running a platform that supports AppFunctions; where it does not,
 * the tests skip rather than fail. The APK registers itself as an AppFunctions provider, so no
 * permission is needed to discover its own functions.
 */
@RunWith(AndroidJUnit4::class)
class AppFunctionsToolsetEndToEndTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val toolset = AppFunctionsToolset(context)

  @Before
  fun assumeAppFunctionsAreSupported() {
    // Ask the platform directly. Inferring support from "no tool turned up" would also swallow a
    // broken converter, a missing service entry or a discovery failure -- the regressions this
    // test exists to catch -- and report the whole suite as passing.
    assumeTrue(
      "This device does not support AppFunctions; skipping the end-to-end test.",
      AppFunctionManager.getInstance(context) != null,
    )
  }

  @Test
  fun getTools_discoversTheFunctionsThisAppDeclares() = runBlocking {
    // Through the wait, like every other test here: the APK is reinstalled on each run, so a
    // freshly indexed platform is the normal case rather than the exception.
    val createNote = awaitTool("createNote")
    val names = toolset.getTools().map { it.name }

    assertThat(names).contains(createNote.name)
    assertThat(names.filter { it.endsWith("_createNote") }).hasSize(1)
    assertThat(names.any { it.endsWith("_echo") }).isTrue()
    // The whole name, not just its tail: this is what the model is handed on every turn.
    assertThat(createNote.name).matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")
  }

  @Test
  fun getTools_declaresTheParametersTheAppFunctionTakes() = runBlocking {
    val declaration = awaitTool("createNote").declaration()

    assertThat(declaration?.parameters?.properties?.keys).containsAtLeast("title", "pages")
    assertThat(declaration?.parameters?.required).contains("title")
  }

  @Test
  fun run_scalarReturn_reachesTheAppAndComesBack() = runBlocking {
    val echo = awaitTool("echo")

    val result = echo.run(testToolContext(), mapOf("value" to "round trip"))

    assertThat(result).isEqualTo(mapOf(BaseTool.RESULT_KEY to "round trip"))
  }

  @Test
  fun run_objectReturn_readsBackThePropertiesTheAppSet() = runBlocking {
    val createNote = awaitTool("createNote")

    val result = createNote.run(testToolContext(), mapOf("title" to "Groceries", "pages" to 3))

    assertThat(result)
      .isEqualTo(mapOf(BaseTool.RESULT_KEY to mapOf("title" to "Groceries", "pages" to 3)))
  }

  @Test
  fun run_argumentOfTheWrongType_isRejectedBeforeReachingTheApp() = runBlocking {
    val createNote = awaitTool("createNote")

    val result = createNote.run(testToolContext(), mapOf("title" to "Groceries", "pages" to 1.5))

    // The converter's own wording, which the app could not have produced: an app-side rejection
    // would come back through `describe()` and would also mention `pages`.
    assertThat(result)
      .isEqualTo(mapOf(FunctionTool.ERROR_KEY to "Parameter 'pages' expects a 32-bit whole number"))
  }

  @Test
  fun getTools_functionReturningAScreen_isNotOffered() = runBlocking {
    // `showNote` is declared by this APK and returns a PendingIntent. Waiting on a function that
    // *is* offered first means an absent `showNote` proves it was filtered, not merely unindexed.
    val offered = awaitTool("createNote")

    val names = toolset.getTools().map { it.name }

    assertThat(names).contains(offered.name)
    assertThat(names.filter { it.endsWith("showNote") }).isEmpty()
  }

  /**
   * The tool whose name ends with [suffix], once the platform has indexed it.
   *
   * A function is not discoverable for a short while after its app is installed, which on a fresh
   * test APK is exactly when the first test runs.
   */
  private suspend fun awaitTool(suffix: String): BaseTool =
    checkNotNull(
      withTimeoutOrNull(INDEXING_TIMEOUT) {
        var found: BaseTool? = null
        while (found == null) {
          found = toolset.getTools().firstOrNull { it.name.endsWith(suffix) }
          if (found == null) delay(POLL_INTERVAL)
        }
        found
      }
    ) {
      "No app function ending in '$suffix' was discovered within $INDEXING_TIMEOUT, on a device " +
        "that supports AppFunctions. Discovered: ${toolset.getTools().map { it.name }}"
    }

  private companion object {
    val INDEXING_TIMEOUT = 90.seconds
    val POLL_INTERVAL = 500.milliseconds
  }
}
