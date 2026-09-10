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

@file:OptIn(ExperimentalAppFunctionsFeature::class, ExperimentalAppFunctionsApi::class)

package com.google.adk.kt.tools.appfunctions

import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.AppFunctionState
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.HandleAppFunctionRequest
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionName
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppFunctionProviderTest {

  /** The registry is a process-wide singleton and JUnit4 orders methods by a hash of the name. */
  @After
  fun clearRegistry() {
    repeat(MAX_LEAKED_CLAIMS) { ServedAppFunctions.remove(PACKAGE, setOf(FUNCTION_ID)) }
  }

  @Test
  fun serveAgent_functionTheAppDoesNotDeclare_isRefusedBeforeRegistering() {
    val server = FakeAppFunctionServer(declared = emptyList())
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(), USER, SESSION) }
      }
    assertThat(failure).hasMessageThat().contains(FUNCTION_ID)
    assertThat(server.served).isNull()
  }

  @Test
  fun serveAgent_signatureThatIsNotOneStringInOneStringOut_isRefused() {
    val twoParameters = metadata(parameters = listOf(stringParameter("a"), stringParameter("b")))
    val server = FakeAppFunctionServer(declared = listOf(twoParameters))
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(), USER, SESSION) }
      }
    assertThat(failure).hasMessageThat().contains("exactly one")
    assertThat(server.served).isNull()
  }

  @Test
  fun serveAgent_recordsTheServedFunctionWhileServing_andClearsItAfterwards() = runBlocking {
    val server = FakeAppFunctionServer(declared = listOf(metadata()))
    val serving = launch {
      AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(), USER, SESSION)
    }
    server.awaitServing()
    assertThat(ServedAppFunctions.isServing(PACKAGE, FUNCTION_ID)).isTrue()

    serving.cancel()
    serving.join()
    assertThat(ServedAppFunctions.isServing(PACKAGE, FUNCTION_ID)).isFalse()
  }

  @Test
  fun serveAgent_cancellation_propagatesAsCancellation_notAsAFailure() = runBlocking {
    // Regression: CancellationException extends IllegalStateException, so the catch translating a
    // platform refusal used to swallow every normal withdrawal and report it as one.
    val server = FakeAppFunctionServer(declared = listOf(metadata()))
    var observed: Throwable? = null
    val serving = launch {
      try {
        AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(), USER, SESSION)
      } catch (t: Throwable) {
        observed = t
        throw t
      }
    }
    server.awaitServing()
    serving.cancel()
    serving.join()

    assertThat(observed).isInstanceOf(CancellationException::class.java)
  }

  @Test
  fun serveAgent_refusedWhileAnotherIsServing_leavesTheLiveOneTracked() = runBlocking {
    // A refused second registrant must not clear the name the first is still serving, or the
    // toolset stops filtering and the agent is offered its own function.
    val live = FakeAppFunctionServer(declared = listOf(metadata()))
    val serving = launch {
      AppFunctionProvider(live).serveAgent(FUNCTION_ID, runner(), USER, SESSION)
    }
    live.awaitServing()

    val refused =
      FakeAppFunctionServer(
        declared = listOf(metadata()),
        refuseWith = IllegalStateException("App function already registered"),
      )
    val failure =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          AppFunctionProvider(refused).serveAgent(FUNCTION_ID, runner(), USER, SESSION)
        }
      }
    assertThat(failure).hasCauseThat().hasMessageThat().contains("already registered")
    assertThat(ServedAppFunctions.isServing(PACKAGE, FUNCTION_ID)).isTrue()

    serving.cancel()
    serving.join()
    assertThat(ServedAppFunctions.isServing(PACKAGE, FUNCTION_ID)).isFalse()
  }

  @Test
  fun theServedFunction_runsOneAgentTurn_andAnswersWithItsReply() = runBlocking {
    val declared = metadata()
    val server = FakeAppFunctionServer(declared = listOf(declared))
    val serving = launch {
      AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(), USER, SESSION)
    }
    server.awaitServing()

    val response = server.call(declared, "hello")

    assertThat(response).isInstanceOf(ExecuteAppFunctionResponse.Success::class.java)
    assertThat(
        (response as ExecuteAppFunctionResponse.Success)
          .returnValue
          .getString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)
      )
      .isEqualTo(REPLY)

    serving.cancel()
    serving.join()
  }

  @Test
  fun aFailingTurn_isReportedToTheCaller_withoutUnregistering() = runBlocking {
    // The SDK rethrows anything that is not an AppFunctionException, which unregisters the function
    // and cancels the scope serving it, so one failed turn would take the whole provider down.
    val declared = metadata()
    val server = FakeAppFunctionServer(declared = listOf(declared))
    val serving = launch {
      AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(FailingAgent()), USER, SESSION)
    }
    server.awaitServing()

    val failure =
      assertThrows(AppFunctionException::class.java) { runBlocking { server.call(declared, "x") } }
    // Shape only: the SDK forwards this text to the calling app.
    assertThat(failure).hasMessageThat().doesNotContain(SECRET)

    assertThat(serving.isActive).isTrue()
    assertThat(ServedAppFunctions.isServing(PACKAGE, FUNCTION_ID)).isTrue()
    serving.cancel()
    serving.join()
  }

  @Test
  fun aThrowingWithdrawal_doesNotReachTheProcessKillingHandler() = runBlocking {
    // The SDK withdraws from an invokeOnCancellation handler. kotlinx routes a throw there to
    // handleCoroutineException, which falls through to the thread's default uncaught handler --
    // process death on Android -- unless a CoroutineExceptionHandler is in the context. Asserting
    // on cancellation state cannot see the difference; asserting on that fallthrough can.
    val reachedDefaultHandler = AtomicReference<Throwable?>(null)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, failure -> reachedDefaultHandler.set(failure) }
    try {
      val server = FakeAppFunctionServer(declared = listOf(metadata()), throwOnWithdrawal = true)
      val serving = launch {
        AppFunctionProvider(server).serveAgent(FUNCTION_ID, runner(), USER, SESSION)
      }
      server.awaitServing()
      serving.cancel()
      serving.join()

      assertThat(serving.isCancelled).isTrue()
      assertThat(reachedDefaultHandler.get()).isNull()
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }
  }

  @Test
  fun theToolset_withholdsAFunctionThisProcessIsServing() = runBlocking {
    val declared = metadata()
    assertThat(toolsetOver(declared, setOf(PACKAGE)).getTools()).isNotEmpty()

    ServedAppFunctions.add(PACKAGE, setOf(FUNCTION_ID))
    assertThat(toolsetOver(declared, setOf(PACKAGE)).getTools()).isEmpty()
  }

  @Test
  fun theToolset_stillOffersAnotherAppsIdenticallyNamedFunction() = runBlocking {
    // Two apps can declare the same identifier; only the package tells them apart.
    ServedAppFunctions.add(PACKAGE, setOf(FUNCTION_ID))
    assertThat(toolsetOver(metadata(packageName = OTHER_PACKAGE), null).getTools()).isNotEmpty()
  }

  private fun toolsetOver(declared: AppFunctionMetadata, packages: Set<String>?) =
    AppFunctionsToolset(FakeAppFunctionClient(listOf(declared)), packages, null)

  private suspend fun runner(agent: BaseAgent = ReplyingAgent()): Runner =
    InMemoryRunner(agent = agent).also {
      val unused = it.sessionService.createSession(SessionKey(it.appName, USER, SESSION))
    }

  private fun stringParameter(name: String) =
    AppFunctionParameterMetadata(
      name = name,
      isRequired = true,
      dataType = AppFunctionStringTypeMetadata(isNullable = false),
    )

  private fun metadata(
    parameters: List<AppFunctionParameterMetadata> = listOf(stringParameter(PARAMETER)),
    packageName: String = PACKAGE,
  ) =
    AppFunctionMetadata(
      id = FUNCTION_ID,
      packageName = packageName,
      isEnabled = true,
      schema = null,
      parameters = parameters,
      response =
        AppFunctionResponseMetadata(valueType = AppFunctionStringTypeMetadata(isNullable = false)),
      components = AppFunctionComponentsMetadata(),
      description = "",
    )

  /** Emits one final response, so a turn needs no model. */
  private class ReplyingAgent : BaseAgent(name = "replying-agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
      emit(
        Event(
          invocationId = context.invocationId,
          author = name,
          content = Content.fromText("model", REPLY),
        )
      )
    }
  }

  /** Fails the way an ADK turn can, carrying text that must not reach the calling app. */
  private class FailingAgent : BaseAgent(name = "failing-agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
      throw IllegalStateException(SECRET)
    }
  }

  /** Serves whatever the toolset is given, so discovery can be driven without a device. */
  private class FakeAppFunctionClient(private val discovered: List<AppFunctionMetadata>) :
    AppFunctionClient {
    override val isSupported: Boolean = true

    override suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata> = discovered

    override suspend fun states(names: List<AppFunctionName>): List<AppFunctionState> = names.map {
      AppFunctionState(it, true)
    }

    override suspend fun execute(request: ExecuteAppFunctionRequest): ExecuteAppFunctionResponse? =
      null
  }

  /** Substitutes the platform so the provider can be driven without a device. */
  private class FakeAppFunctionServer(
    private val declared: List<AppFunctionMetadata>,
    private val refuseWith: Throwable? = null,
    private val throwOnWithdrawal: Boolean = false,
  ) : AppFunctionServer {

    @Volatile var served: List<HandleAppFunctionRequest>? = null

    @Volatile private var servingScope: CoroutineScope? = null

    override val isSupported: Boolean = true

    override val packageName: String = PACKAGE

    override suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata> = declared

    /** Mirrors the SDK: registered inside the continuation, withdrawn from its cancel handler. */
    override suspend fun serve(requests: List<HandleAppFunctionRequest>): Nothing {
      refuseWith?.let { throw it }
      if (throwOnWithdrawal) {
        suspendCancellableCoroutine<Nothing> { continuation ->
          served = requests
          continuation.invokeOnCancellation { throw RuntimeException("binder died") }
        }
      }
      coroutineScope {
        servingScope = this
        served = requests
        awaitCancellation()
      }
    }

    /**
     * Invokes the registered implementation the way the platform would.
     *
     * The SDK runs it inside the scope that is serving and rethrows anything that is not an
     * [AppFunctionException], which is what unregisters the function; reproducing that is what lets
     * a test tell a survivable failure from a fatal one.
     */
    suspend fun call(metadata: AppFunctionMetadata, prompt: String): ExecuteAppFunctionResponse {
      val request =
        ExecuteAppFunctionRequest(
          targetPackageName = PACKAGE,
          functionIdentifier = FUNCTION_ID,
          functionParameters =
            AppFunctionData.Builder(metadata.parameters, metadata.components)
              .setString(PARAMETER, prompt)
              .build(),
        )
      val answer = CompletableDeferred<ExecuteAppFunctionResponse>()
      checkNotNull(servingScope).launch {
        try {
          answer.complete(checkNotNull(served).single().appFunction.executeAppFunction(request))
        } catch (t: AppFunctionException) {
          answer.completeExceptionally(t)
        } catch (t: Throwable) {
          answer.completeExceptionally(t)
          throw t
        }
      }
      return answer.await()
    }

    /** Waits for [serve] to have registered, rather than sleeping a guessed interval. */
    suspend fun awaitServing() {
      withTimeout(AWAIT_MILLIS) { while (served == null) yield() }
    }
  }

  private companion object {
    private const val PACKAGE = "com.example.app"
    private const val OTHER_PACKAGE = "com.example.other"
    private const val FUNCTION_ID = "com.example.app.AskMyAgent#ask"
    private const val PARAMETER = "request"
    private const val USER = "user"
    private const val SESSION = "session"
    private const val REPLY = "the agent answered"
    private const val SECRET = "what the user privately said"
    private const val AWAIT_MILLIS = 5_000L
    private const val MAX_LEAKED_CLAIMS = 4
  }
}
