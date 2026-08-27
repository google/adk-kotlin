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

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionCancelledException
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionElementAlreadyExistsException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionFunctionNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionLimitExceededException
import androidx.appfunctions.AppFunctionNotSupportedException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSystemUnknownException
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import kotlinx.coroutines.CancellationException

/**
 * One Android AppFunction, exposed to an agent as an ADK tool.
 *
 * The declaration is converted once when the toolset discovers the function; a failure to execute
 * is reported to the model as a result rather than thrown, so the agent can correct itself. A
 * failed call is never retried here, since the target app may already have run part of it.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AppFunctionTool(
  private val metadata: AppFunctionMetadata,
  private val declaration: FunctionDeclaration,
  private val client: AppFunctionClient,
) : BaseTool(name = declaration.name, description = declaration.description) {

  override fun declaration(): FunctionDeclaration = declaration

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val parameters =
      try {
        AppFunctionDataConverter.toAppFunctionData(metadata, args)
      } catch (e: IllegalArgumentException) {
        // The converter keeps the value out of this message, so it is safe to hand back to the
        // model -- which is the only way it learns what to correct.
        return mapOf(ERROR_KEY to (e.message ?: "The arguments did not fit the function"))
      }

    val response =
      try {
        client.execute(
          ExecuteAppFunctionRequest(
            targetPackageName = metadata.packageName,
            functionIdentifier = metadata.id,
            functionParameters = parameters,
          )
        )
      } catch (e: CancellationException) {
        // The caller gave up on this turn; that has to keep unwinding.
        throw e
      } catch (e: Exception) {
        // The platform can refuse the call outright rather than answering with an Error. An
        // AppFunctionException's message is the app's own text, so only its category is logged.
        if (e is AppFunctionException) logger.warn { "App function $name failed: ${e.category()}." }
        else logger.warn(e) { "App function $name could not be invoked." }
        return mapOf(ERROR_KEY to "The app function could not be invoked")
      } ?: return mapOf(ERROR_KEY to "App functions are unavailable on this device")

    return when (response) {
      is ExecuteAppFunctionResponse.Success -> readSuccess(response)
      is ExecuteAppFunctionResponse.Error -> {
        logger.warn { "App function $name failed with ${response.error.category()}." }
        mapOf(ERROR_KEY to response.error.describe())
      }
    }
  }

  /**
   * Reads a successful response, reporting an unreadable one as an error.
   *
   * The metadata is read at discovery and the value at execution, so an app updated in between can
   * return something the declared response does not describe. A response carrying only screens says
   * so rather than reporting a bare success.
   */
  private fun readSuccess(response: ExecuteAppFunctionResponse.Success): Map<String, Any?> {
    val declared = metadata.response.valueType
    val value =
      try {
        AppFunctionDataConverter.fromReturnValue(metadata, response.returnValue)
      } catch (e: RuntimeException) {
        // The converter wraps what the library threw in a `MalformedResponse` of its own, without
        // chaining, so this throwable carries fixed text rather than the app's returned value.
        logger.warn(e) { "App function $name returned a value that does not match its metadata." }
        return mapOf(ERROR_KEY to "The app function returned a value that could not be read")
      }
    // Only when nothing readable came back: a response that also carries data returns the data,
    // and the screens alongside it are dropped like any other unrepresentable property.
    if (
      value.isEmpty() &&
        AppFunctionTypes.carriesUndeliverablePendingIntent(declared, metadata.components)
    ) {
      logger.warn { "App function $name returned only screens, which are not values to read." }
      return mapOf(ERROR_KEY to "The app function returned screens rather than a value")
    }
    return mapOf(BaseTool.RESULT_KEY to value)
  }

  /** Whether the app's converted return value carries nothing the model could read. */
  private fun Any?.isEmpty(): Boolean =
    this == null || (this is Map<*, *> && isEmpty()) || (this is Collection<*> && isEmpty())

  private companion object {
    private val ERROR_KEY = FunctionTool.ERROR_KEY

    private val logger = LoggerFactory.getLogger(AppFunctionTool::class)

    /**
     * What the model is told about a failure.
     *
     * The app's own message says the most about what went wrong, so it is passed through; the
     * stable category stands in when the app supplied none.
     */
    private fun AppFunctionException.describe(): String =
      errorMessage?.takeIf { it.isNotBlank() } ?: category()
  }
}

/**
 * A stable name for the kind of failure, safe to log.
 *
 * Derived by type rather than from the exception's class name, which minification rewrites, and
 * from its error code, which the library does not expose. Unlike the exception's own message, which
 * is the app's text, this carries nothing the app wrote.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun AppFunctionException.category(): String =
  when (this) {
    is AppFunctionDeniedException -> "DENIED"
    is AppFunctionInvalidArgumentException -> "INVALID_ARGUMENT"
    is AppFunctionDisabledException -> "DISABLED"
    is AppFunctionFunctionNotFoundException -> "FUNCTION_NOT_FOUND"
    is AppFunctionElementNotFoundException -> "ELEMENT_NOT_FOUND"
    is AppFunctionElementAlreadyExistsException -> "ELEMENT_ALREADY_EXISTS"
    is AppFunctionLimitExceededException -> "LIMIT_EXCEEDED"
    is AppFunctionPermissionRequiredException -> "PERMISSION_REQUIRED"
    is AppFunctionNotSupportedException -> "NOT_SUPPORTED"
    is AppFunctionCancelledException -> "CANCELLED"
    // What the library reports whenever the called app's own code throws, so it is the
    // failure most often seen; worth telling apart from a genuine version skew.
    is AppFunctionAppUnknownException -> "APP_FAILED"
    is AppFunctionSystemUnknownException -> "SYSTEM_FAILED"
    else -> "UNKNOWN"
  }
