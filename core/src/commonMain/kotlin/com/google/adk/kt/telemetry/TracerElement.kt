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

package com.google.adk.kt.telemetry

import com.google.adk.kt.telemetry.noop.NoOpTracer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Carries the run-scoped [Tracer] on the coroutine context.
 *
 * The runner installs this once (from `App.tracer`) so every span operator downstream resolves the
 * same tracer via [currentTracer] without any function needing a `Tracer` parameter. Telemetry is
 * entirely instance-scoped: there is no global tracer.
 */
internal class TracerElement(val tracer: Tracer) : AbstractCoroutineContextElement(TracerElement) {
  companion object Key : CoroutineContext.Key<TracerElement>
}

/**
 * The [Tracer] for the current coroutine: the run-scoped one installed via [TracerElement] (from
 * `App.tracer`) if present, otherwise [NoOpTracer]. There is no global default -- telemetry is off
 * unless an `App.tracer` is configured.
 */
internal suspend fun currentTracer(): Tracer =
  currentCoroutineContext()[TracerElement]?.tracer ?: NoOpTracer
