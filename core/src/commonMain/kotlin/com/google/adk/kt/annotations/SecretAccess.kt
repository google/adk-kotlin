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
 * Marks APIs that return a secret's raw value, such as
 * [Secret.reveal][com.google.adk.kt.auth.Secret.reveal]. Opt in with `@OptIn(SecretAccess::class)`,
 * which makes each place that handles a raw credential visible in review.
 */
@ExperimentalAuthApi
@MustBeDocumented
@RequiresOptIn(message = "Reveals a secret value.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.TYPEALIAS,
)
annotation class SecretAccess
