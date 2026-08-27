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

import android.app.PendingIntent
import com.google.adk.kt.annotations.ExperimentalAppFunctionsFeature

/**
 * Opens a screen an app function answered with, in place of data.
 *
 * A functional (SAM) interface, so it is a lambda from Kotlin and from Java alike:
 * `AppFunctionsToolset(context) { it.send() }`.
 */
@ExperimentalAppFunctionsFeature
fun interface AppFunctionUiActionHandler {
  /** Called with the screen to open, on whichever dispatcher the agent is running on. */
  fun onUiAction(pendingIntent: PendingIntent)
}
