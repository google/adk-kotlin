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

import android.app.Activity
import android.os.Bundle
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.types.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Publishes [ASK_TEST_AGENT_ID] for as long as it is resumed, which is how an app is expected to
 * use the provider.
 *
 * The agent is a [DummyAgent] echoing a fixed reply, so the test proves the provider's plumbing
 * without reaching a model.
 */
@OptIn(ExperimentalAppFunctionsFeature::class)
class ProviderTestActivity : Activity() {

  /** Set when serving fails, so a test can assert on the cause rather than on a timeout. */
  @Volatile var serveError: Throwable? = null

  private var job: Job? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    serveError = null
  }

  override fun onResume() {
    super.onResume()
    val provider = AppFunctionProvider(this)
    if (!provider.isSupported) return
    val runner = InMemoryRunner(agent = DummyAgent(onRunAsync = { emit(reply(it.invocationId)) }))
    job =
      CoroutineScope(Dispatchers.Main.immediate).launch {
        try {
          val unused =
            runner.sessionService.createSession(SessionKey(runner.appName, USER_ID, SESSION_ID))
          provider.serveAgent(ASK_TEST_AGENT_ID, runner, USER_ID, SESSION_ID)
        } catch (e: CancellationException) {
          // Pausing cancels this scope; that is not a failure and must not be recorded as one.
          throw e
        } catch (t: Throwable) {
          // Recorded rather than rethrown: an uncaught throw from this bare scope reaches the
          // default handler and kills the process before a test can read the field.
          serveError = t
        }
      }
  }

  override fun onPause() {
    job?.cancel()
    job = null
    super.onPause()
  }

  private fun reply(invocationId: String): Event =
    Event(invocationId = invocationId, author = AGENT, content = Content.fromText(MODEL, REPLY))

  companion object {
    const val USER_ID: String = "provider-test-user"
    const val SESSION_ID: String = "provider-test-session"
    const val REPLY: String = "the agent answered"
    private const val AGENT = "test-agent"
    private const val MODEL = "model"
  }
}
