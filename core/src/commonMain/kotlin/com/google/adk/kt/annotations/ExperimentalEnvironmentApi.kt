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

package com.google.adk.kt.annotations

/**
 * Marks ADK's **environment** APIs (execution environments and the environment toolset) as
 * experimental: their shape and semantics may change in future releases without prior notice.
 *
 * Opt in explicitly with `@OptIn(ExperimentalEnvironmentApi::class)` to use them.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message =
    "ADK environment APIs are experimental and may change at any time. " +
      "Opt in with @OptIn(ExperimentalEnvironmentApi::class) to acknowledge the risk.",
)
annotation class ExperimentalEnvironmentApi
