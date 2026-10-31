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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.classifiers.Classifier
import com.google.adk.kt.classifiers.ClassifierResponse
import com.google.adk.kt.classifiers.Question
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future

/**
 * Java-friendly wrapper around a [Classifier] that it owns: [classify] calls [Classifier.classify]
 * in the caller's OpenTelemetry context and the passed `coroutineContext`, returning a
 * [CompletableFuture] of the result. Canceling a future cancels its call, and a `Job` in
 * `coroutineContext` is ignored.
 */
@AdkJavaInteropApi
@ExperimentalClassifierApi
class CompletableFutureClassifier private constructor(private val delegate: Classifier) :
  AutoCloseable {

  // Runs each call as a coroutine that completes its future; canceled by close().
  @Suppress("GlobalCoroutineDispatchers")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The wrapped classifier, for code that has dropped into Kotlin. */
  fun asKotlinClassifier(): Classifier = delegate

  /** Calls [Classifier.classify]. */
  @JvmOverloads
  fun classify(
    input: Any,
    questions: Map<String, Question>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<ClassifierResponse> =
    scope.future(callerContext(coroutineContext)) { delegate.classify(input, questions) }

  /**
   * Cancels calls in flight, then closes the classifier if it is [AutoCloseable]. Every later call
   * returns a canceled future.
   */
  override fun close() {
    scope.cancel()
    (delegate as? AutoCloseable)?.close()
  }

  companion object {
    /**
     * Wraps [classifier] and takes ownership of it: [close] also closes it if it is
     * [AutoCloseable].
     */
    @JvmStatic
    fun of(classifier: Classifier): CompletableFutureClassifier =
      CompletableFutureClassifier(classifier)
  }
}
