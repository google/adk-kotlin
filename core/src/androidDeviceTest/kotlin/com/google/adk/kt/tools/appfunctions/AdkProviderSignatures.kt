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

import androidx.appfunctions.AppFunctionSignature
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata

/**
 * The app function this test APK publishes, declared the way an app using `AppFunctionProvider`
 * declares its own: by hand, in the module the AppFunctions compiler runs on.
 *
 * Globally scoped because an activity-scoped function is reachable only by a caller holding an
 * `AppFunctionActivityId`, which only the system assistant can mint.
 */
@OptIn(ExperimentalAppFunctionsApi::class)
@AppFunctionSignature(
  scope = AppFunctionMetadata.SCOPE_GLOBAL,
  appFunctionXmlFileName = "adk_provider_functions",
  isDescribedByKDoc = true,
)
fun interface AskTestAgent {
  /**
   * Asks this app's agent to do something and returns what it said.
   *
   * @param request What to ask, in plain language.
   * @return The agent's reply.
   */
  suspend fun ask(request: String): String
}

/** The identifier the compiler derives for [AskTestAgent], which the provider binds to. */
const val ASK_TEST_AGENT_ID: String = "com.google.adk.kt.tools.appfunctions.AskTestAgent#ask"
