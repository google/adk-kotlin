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

package com.google.adk.kt.examples.android.common

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Base [Activity] for the examples that run an agent off the main thread.
 *
 * Provides a single [scope] whose lifetime is tied to the Activity: it is cancelled in [onDestroy],
 * so coroutines launched from it stop when the user leaves (back-press, `finish()`, or process
 * death) instead of running on — and posting UI updates to — a destroyed Activity.
 */
abstract class ScopedExampleActivity : Activity() {

  /**
   * Coroutine scope for agent work. Coroutines launch on the default dispatcher; UI updates are
   * marshaled back via [runOnUiThread]. Cancelled in [onDestroy].
   */
  protected val scope = CoroutineScope(SupervisorJob())

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
