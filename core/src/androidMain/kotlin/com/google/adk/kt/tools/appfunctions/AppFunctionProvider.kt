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
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.HandleAppFunctionRequest
import androidx.appfunctions.SuspendingAppFunction
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.types.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Publishes this app's agent as an Android AppFunction, so another app or the system can ask it
 * something while a session is live.
 *
 * The app declares the function itself, at build time, as a `fun interface` annotated
 * `@AppFunctionSignature`; this class binds the implementation and holds it only for as long as the
 * calling coroutine runs.
 *
 * ```
 * val provider = AppFunctionProvider(this)
 * if (provider.isSupported) {
 *   lifecycleScope.launch {
 *     repeatOnLifecycle(Lifecycle.State.RESUMED) {
 *       try {
 *         provider.serveAgent("com.example.AskMyAgent#ask", runner, userId, sessionId)
 *       } catch (e: CancellationException) {
 *         throw e // Ordinary teardown, and it would match the catch below.
 *       } catch (e: IllegalStateException) {
 *         // Another instance of this screen is already serving the function.
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * The declared function must take one required string parameter and return a string. AppFunctions
 * needs Android 17 for a runtime-bound function; below that [isSupported] is false and [serveAgent]
 * refuses rather than failing at the platform.
 *
 * Runtime registration is additionally gated on the platform's `enable_dynamic_app_functions` flag.
 * A build with that flag off treats every declared function as static, so registration fails and
 * this provider serves nothing however it is configured.
 *
 * ADK declares `androidx.appfunctions` as `compileOnly`, so an app using this must add that
 * dependency itself. An app that also registers an `AppFunctionService` should enable the
 * AppFunctions compiler's aggregation mode; without it that service fails to resolve its generated
 * invoker when it binds. Nothing here depends on that mode.
 */
@ExperimentalAppFunctionsFeature
@OptIn(ExperimentalAppFunctionsApi::class)
class AppFunctionProvider internal constructor(private val server: AppFunctionServer) {

  /**
   * @param context an Activity or Service context. An application context cannot register a
   *   function, and neither can a broadcast receiver's.
   */
  constructor(context: Context) : this(PlatformAppFunctionServer(context))

  /**
   * Whether this device can serve a runtime-bound app function at all.
   *
   * Annotated so lint narrows the version check through it, the way [AppFunctionsToolset] tests
   * `SDK_INT` inline for the same reason.
   */
  @get:ChecksSdkIntAtLeast(api = ANDROID_17)
  val isSupported: Boolean
    get() = server.isSupported

  /**
   * Serves [functionId] as [runner]'s agent until the calling coroutine is cancelled, then
   * withdraws it.
   *
   * Never returns normally. Each call the function receives runs one agent turn against [sessionId]
   * and answers with the final response's text.
   *
   * A globally scoped function permits one live implementation and the platform is the only
   * authority on that, so a second registrant of one name -- another instance of an activity across
   * tasks, or in multi-window -- is refused here rather than serialised, and the refusal leaves the
   * first one serving. A recreated activity re-registers cleanly.
   *
   * Overlapping calls to one served function share [sessionId] and are not serialised, so they
   * interleave in that session.
   *
   * @throws IllegalStateException if the platform refuses the registration, or if the device cannot
   *   serve app functions.
   * @throws IllegalArgumentException if [functionId] is not declared by this app, or is not shaped
   *   as one required string parameter returning a string.
   */
  @RequiresApi(ANDROID_17)
  suspend fun serveAgent(
    functionId: String,
    runner: Runner,
    userId: String,
    sessionId: String,
  ): Nothing {
    check(server.isSupported) {
      "This device cannot serve app functions; check isSupported before calling serveAgent."
    }
    val metadata = declaredMetadata(functionId)
    val parameterName = singleStringParameterName(functionId, metadata)

    ServedAppFunctions.add(server.packageName, setOf(functionId))
    try {
      // A withdrawal throw reaches handleCoroutineException(context), not the Job
      // hierarchy, so this is read though it is not a root coroutine.
      withContext(
        CoroutineExceptionHandler { _, failure ->
          logger.warn { "Withdrawing an app function failed: ${failure::class.simpleName}." }
        }
      ) {
        server.serve(
          listOf(
            HandleAppFunctionRequest(
              functionId,
              SuspendingAppFunction { request ->
                answer(request, metadata, parameterName, runner, userId, sessionId)
              },
            )
          )
        )
      }
    } catch (e: CancellationException) {
      // CancellationException extends IllegalStateException, so this must not fall through.
      throw e
    } catch (e: IllegalStateException) {
      // One call does both, so [answer] is what keeps turn failures out of here.
      throw IllegalStateException(
        "The platform refused to serve $functionId. Check that nothing else in this app is " +
          "already serving it, and that this provider was built from an Activity or Service " +
          "context rather than the application context.",
        e,
      )
    } finally {
      ServedAppFunctions.remove(server.packageName, setOf(functionId))
    }
  }

  /**
   * Answers one call, converting any failure into an [AppFunctionException].
   *
   * The SDK reports an [AppFunctionException] to the caller and leaves the registration alone, but
   * rethrows anything else -- which unregisters the function and cancels the scope serving it, so
   * one failed turn would otherwise take the whole provider down with it.
   */
  // Dropped rather than chained: an ADK failure's message can carry model or session content.
  @Suppress("UnusedException")
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun answer(
    request: ExecuteAppFunctionRequest,
    metadata: AppFunctionMetadata,
    parameterName: String,
    runner: Runner,
    userId: String,
    sessionId: String,
  ): ExecuteAppFunctionResponse =
    try {
      val prompt =
        request.functionParameters.getString(parameterName)
          ?: throw AppFunctionInvalidArgumentException("Missing parameter $parameterName.")
      respond(metadata, runTurn(runner, userId, sessionId, prompt))
    } catch (e: CancellationException) {
      throw e
    } catch (e: AppFunctionException) {
      throw e
    } catch (t: Throwable) {
      // Shape only: the SDK puts this text in the response it sends to the calling app, and an ADK
      // failure's message can carry model or session content.
      logger.warn { "An app function turn failed: ${t::class.simpleName}." }
      throw AppFunctionAppUnknownException("The agent could not answer this call.")
    }

  /** Runs one turn and returns the final response's text, joined across its parts. */
  private suspend fun runTurn(
    runner: Runner,
    userId: String,
    sessionId: String,
    prompt: String,
  ): String {
    val events =
      runner
        .runAsync(
          userId = userId,
          sessionId = sessionId,
          newMessage = Content.fromText(USER, prompt),
        )
        .toList()
    val reply =
      events
        .lastOrNull { it.isFinalResponse }
        ?.content
        ?.parts
        ?.mapNotNull { it.text }
        ?.joinToString(separator = "")
    // Lengths, not content: a reply is customer data and must not reach a log line.
    logger.info {
      "Served an app function turn: ${events.size} events, ${reply?.length ?: 0} chars."
    }
    return reply
      ?: throw AppFunctionAppUnknownException("The agent produced no final response for this call.")
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun respond(metadata: AppFunctionMetadata, reply: String): ExecuteAppFunctionResponse =
    ExecuteAppFunctionResponse.Success(
      AppFunctionData.Builder(metadata.response, metadata.components)
        .setString(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, reply)
        .build()
    )

  /**
   * The app's own declaration of [functionId], which is what the platform validates calls against.
   */
  private suspend fun declaredMetadata(functionId: String): AppFunctionMetadata =
    server.search(AppFunctionSearchSpec(packageNames = setOf(server.packageName))).firstOrNull {
      it.id == functionId
    }
      ?: throw IllegalArgumentException(
        "$functionId is not declared by this app. Declare it with @AppFunctionSignature and name " +
          "the generated XML in an application-level <property android:name=\"android.app.appfunctions\">."
      )

  /**
   * The name of the one string parameter [functionId] declares.
   *
   * Checked here rather than at call time so a mismatched signature fails when the app starts
   * serving, not when a caller first arrives.
   */
  private fun singleStringParameterName(functionId: String, metadata: AppFunctionMetadata): String {
    val parameter =
      metadata.parameters.singleOrNull()
        ?: throw IllegalArgumentException(
          "$functionId declares ${metadata.parameters.size} parameters; serveAgent needs exactly one."
        )
    require(parameter.dataType is AppFunctionStringTypeMetadata) {
      "$functionId's parameter ${parameter.name} is not a string, which serveAgent needs."
    }
    require(metadata.response.valueType is AppFunctionStringTypeMetadata) {
      "$functionId does not return a string, which serveAgent needs."
    }
    return parameter.name
  }

  private companion object {
    private const val USER = "user"

    private val logger = LoggerFactory.getLogger(AppFunctionProvider::class)
  }
}

/**
 * `Build.VERSION_CODES.CINNAMON_BUN`, as a literal because that constant does not exist at the
 * compile SDK this module targets.
 */
private const val ANDROID_17 = 37

/**
 * The AppFunctions platform calls the provider makes.
 *
 * [AppFunctionManager] is final and reaching it needs a device, so the calls sit behind this seam
 * and tests substitute their own.
 */
@OptIn(ExperimentalAppFunctionsApi::class)
internal interface AppFunctionServer {
  /** Whether this device can serve a runtime-bound app function. */
  val isSupported: Boolean

  /** The package whose declarations are read, which is always the serving app's own. */
  val packageName: String

  /** Returns the functions matching [spec], or nothing when the device has no AppFunctions. */
  suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata>

  /** Holds [requests] registered until the calling coroutine is cancelled. */
  suspend fun serve(requests: List<HandleAppFunctionRequest>): Nothing
}

/**
 * The [AppFunctionServer] backed by the platform.
 *
 * Unlike the toolset's client this keeps the context it was given: registering needs an Activity or
 * Service, and an application context is neither.
 */
@OptIn(ExperimentalAppFunctionsApi::class)
internal class PlatformAppFunctionServer(private val context: Context) : AppFunctionServer {

  private val manager: AppFunctionManager? by lazy { AppFunctionManager.getInstance(context) }

  override val isSupported: Boolean
    get() = Build.VERSION.SDK_INT >= ANDROID_17 && manager != null

  override val packageName: String
    get() = context.packageName

  override suspend fun search(spec: AppFunctionSearchSpec): List<AppFunctionMetadata> =
    manager?.searchAppFunctions(spec).orEmpty()

  @RequiresApi(ANDROID_17)
  override suspend fun serve(requests: List<HandleAppFunctionRequest>): Nothing =
    checkNotNull(manager) { "This device cannot serve app functions." }.handleAppFunctions(requests)
}
