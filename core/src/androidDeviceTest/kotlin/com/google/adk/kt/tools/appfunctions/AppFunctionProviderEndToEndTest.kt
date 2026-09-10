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

package com.google.adk.kt.tools.appfunctions

import android.content.Context
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionName
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [AppFunctionProvider] against the real platform: a function this APK declares is bound at
 * runtime, reachable while the activity serving it is resumed, and gone once it is not.
 *
 * This APK is both provider and caller. That is what lets it prove the self-call guard, and it is
 * also why it cannot prove that the toolset *offers* a dynamically registered function -- the guard
 * deliberately hides one this process is serving. A second APK would not prove it here either,
 * since cross-app discovery needs a permission that is not grantable on the images this runs
 * against.
 */
@OptIn(ExperimentalAppFunctionsFeature::class, ExperimentalAppFunctionsApi::class)
@RunWith(AndroidJUnit4::class)
class AppFunctionProviderEndToEndTest {

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  private val self: String
    get() = context.packageName

  private val manager: AppFunctionManager
    get() = checkNotNull(AppFunctionManager.getInstance(context))

  @Before
  fun requireRuntimeRegistration() {
    // Runtime-bound app functions need Android 17. Reported rather than inferred from an empty
    // result, which would report this suite green for the regressions it exists to catch.
    assumeTrue("This device cannot serve app functions.", AppFunctionProvider(context).isSupported)
  }

  private suspend fun declared(): AppFunctionMetadata =
    manager.searchAppFunctions(AppFunctionSearchSpec(packageNames = setOf(self))).single {
      it.id == ASK_TEST_AGENT_ID
    }

  private suspend fun isEnabled(): Boolean =
    manager
      .getAppFunctionStates(listOf(AppFunctionName(self, ASK_TEST_AGENT_ID)))
      .single()
      .isEnabled

  /**
   * A runner for a second, competing registrant; the agent never runs, the registration is refused.
   */
  private suspend fun secondRunner(): Runner =
    InMemoryRunner(agent = DummyAgent()).also {
      val unused =
        it.sessionService.createSession(SessionKey(it.appName, OTHER_USER, OTHER_SESSION))
    }

  /** Invokes the served function and returns the agent's reply. */
  private suspend fun callTheFunction(): String? {
    val metadata = declared()
    val response =
      manager.executeAppFunction(
        ExecuteAppFunctionRequest(
          targetPackageName = self,
          functionIdentifier = ASK_TEST_AGENT_ID,
          functionParameters =
            AppFunctionData.Builder(metadata.parameters, metadata.components)
              .setString(REQUEST_PARAMETER, "anything")
              .build(),
        )
      )
    return (response as ExecuteAppFunctionResponse.Success)
      .returnValue
      .getString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)
  }

  private suspend fun offeredToolNames(): List<String> =
    AppFunctionsToolset(context).use { it.getTools().map { tool -> tool.name } }

  @Test
  fun declaredFunction_isDiscoverableBeforeAnythingServesIt() = runBlocking {
    val metadata = declared()
    assertThat(metadata.id).isEqualTo(ASK_TEST_AGENT_ID)
    assertThat(isEnabled()).isFalse()
    // A runtime-bound function carries its schema inline rather than from a schema inventory, so
    // the shape the provider and the toolset both read back is asserted rather than assumed.
    assertThat(metadata.parameters.single().name).isEqualTo(REQUEST_PARAMETER)
    assertThat(metadata.parameters.single().dataType)
      .isInstanceOf(AppFunctionStringTypeMetadata::class.java)
    assertThat(metadata.response.valueType).isInstanceOf(AppFunctionStringTypeMetadata::class.java)
  }

  @Test
  fun whileServing_platformReportsEnabled_butTheToolsetWithholdsOurOwnFunction() = runBlocking {
    ActivityScenario.launch(ProviderTestActivity::class.java).use { scenario ->
      awaitEnabled(expected = true)
      scenario.onActivity { assertThat(it.serveError).isNull() }

      // The pair is the point: the platform says the function is live, and the toolset still does
      // not offer it. Only the self-call guard can produce that, since the disabled filter cannot.
      assertThat(isEnabled()).isTrue()
      assertThat(offeredToolNames().filter { OUR_FUNCTION in it }).isEmpty()
    }
  }

  @Test
  fun aSecondLiveRegistrantOfTheSameName_isRefused_andTheFirstKeepsServing() = runBlocking {
    ActivityScenario.launch(ProviderTestActivity::class.java).use { scenario ->
      awaitEnabled(expected = true)
      lateinit var activity: ProviderTestActivity
      scenario.onActivity { activity = it }

      // A globally scoped registration is keyed on package and id with an empty scope, so a second
      // one collides whichever context registers it -- the same refusal two activity instances
      // across tasks, or in multi-window, would produce.
      val refusal =
        withTimeoutOrNull(SECOND_REGISTRANT_MILLIS) {
          withContext(Dispatchers.Main) {
            runCatching {
                AppFunctionProvider(activity)
                  .serveAgent(ASK_TEST_AGENT_ID, secondRunner(), OTHER_USER, OTHER_SESSION)
              }
              .exceptionOrNull()
          }
        }
      // Not merely IllegalStateException: CancellationException extends it, so a second registrant
      // that simply hung would time out and satisfy that on its own. The refusal has to be the
      // translated one.
      assertWithMessage("the second registrant neither returned nor threw")
        .that(refusal)
        .isNotNull()
      assertThat(refusal).isNotInstanceOf(CancellationException::class.java)
      assertThat(refusal).isInstanceOf(IllegalStateException::class.java)
      assertThat(refusal).hasMessageThat().contains("refused to serve")

      // The refusal must leave the first registrant untouched: still live to the platform, still
      // answering, and still withheld from this app's own agent -- that last one is the registry
      // refusing to forget a name it is still serving.
      assertThat(isEnabled()).isTrue()
      assertThat(callTheFunction()).isEqualTo(ProviderTestActivity.REPLY)
      assertThat(offeredToolNames().filter { OUR_FUNCTION in it }).isEmpty()
    }
  }

  @Test
  fun afterTheActivityGoes_theFunctionIsNoLongerEnabled() = runBlocking {
    ActivityScenario.launch(ProviderTestActivity::class.java).use { awaitEnabled(expected = true) }
    awaitEnabled(expected = false)
    assertThat(isEnabled()).isFalse()
  }

  @Test
  fun whileServing_theFunctionRunsTheAgentAndReturnsItsReply() = runBlocking {
    ActivityScenario.launch(ProviderTestActivity::class.java).use {
      awaitEnabled(expected = true)
      val metadata = declared()
      val response =
        manager.executeAppFunction(
          ExecuteAppFunctionRequest(
            targetPackageName = self,
            functionIdentifier = ASK_TEST_AGENT_ID,
            functionParameters =
              AppFunctionData.Builder(metadata.parameters, metadata.components)
                .setString(REQUEST_PARAMETER, "anything")
                .build(),
          )
        )
      assertThat(response).isInstanceOf(ExecuteAppFunctionResponse.Success::class.java)
      val returned =
        (response as ExecuteAppFunctionResponse.Success)
          .returnValue
          .getString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)
      assertThat(returned).isEqualTo(ProviderTestActivity.REPLY)
    }
  }

  @Test
  fun aRecreatedActivityServesAgain() = runBlocking {
    ActivityScenario.launch(ProviderTestActivity::class.java).use { scenario ->
      awaitEnabled(expected = true)
      assertThat(isEnabled()).isTrue()
      repeat(RECREATES) { scenario.recreate() }
      awaitEnabled(expected = true)
      scenario.onActivity { assertThat(it.serveError).isNull() }
      assertThat(isEnabled()).isTrue()
    }
  }

  /** Waits for the platform's view of the registration to settle, which is not instantaneous. */
  private suspend fun awaitEnabled(expected: Boolean) {
    repeat(POLLS) {
      if (isEnabled() == expected) return
      kotlinx.coroutines.delay(POLL_INTERVAL_MILLIS)
    }
    // Said here rather than left to a bare assertion failure, which would not name the wait.
    assertWithMessage("enabled never became $expected within ${POLLS * POLL_INTERVAL_MILLIS}ms")
      .that(isEnabled())
      .isEqualTo(expected)
  }

  private companion object {
    private const val OUR_FUNCTION = "AskTestAgent"
    private const val REQUEST_PARAMETER = "request"
    private const val RECREATES = 3
    private const val OTHER_USER = "second-registrant-user"
    private const val OTHER_SESSION = "second-registrant-session"
    private const val SECOND_REGISTRANT_MILLIS = 15_000L
    private const val POLLS = 40
    private const val POLL_INTERVAL_MILLIS = 250L
  }
}
