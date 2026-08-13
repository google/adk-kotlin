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

package com.google.adk.kt.tools

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Java-friendly base for implementing a [BaseTool]. A Java subclass overrides [declaration] and the
 * plain, blocking [runBlocking]; this base runs the latter on [ioContext] and adapts it to the
 * `suspend` [run] contract that Java cannot express. Leave [ioContext] at its default to run
 * blocking work off the agent's dispatcher, or inject one to control where it runs.
 */
abstract class BlockingTool
@JvmOverloads
constructor(
  name: String,
  description: String,
  isLongRunning: Boolean = false,
  customMetadata: Map<String, Any> = emptyMap(),
  @Suppress("GlobalCoroutineDispatchers") private val ioContext: CoroutineContext = Dispatchers.IO,
) : BaseTool(name, description, isLongRunning, customMetadata) {

  /**
   * Blocking counterpart of [run]; implement the tool's logic here. Runs on [ioContext].
   *
   * @see BaseTool.run
   */
  protected abstract fun runBlocking(context: ToolContext, args: Map<String, Any?>): Any

  final override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    withContext(ioContext) { runBlocking(context, args) }
}
