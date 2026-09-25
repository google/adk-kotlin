/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

/**
 * Marks the `/apps/{appName}/app-info` endpoint as experimental: its response shape and behavior
 * may change in future releases without prior notice.
 *
 * Opt in explicitly with `@OptIn(ExperimentalAppInfoFeature::class)` to enable it.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message =
    "The app-info endpoint is an experimental feature whose API may change at any time. " +
      "Opt in with @OptIn(ExperimentalAppInfoFeature::class) to acknowledge the risk.",
)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
)
annotation class ExperimentalAppInfoFeature
