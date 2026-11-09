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
import com.google.adk.kt.classifiers.ClassifierRequest
import com.google.adk.kt.classifiers.ClassifierResponse
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await

/**
 * Java-friendly base for implementing a [Classifier]: a Java subclass overrides [classifyAsync] to
 * return a [CompletableFuture], which [classify] awaits. Canceling the calling coroutine cancels
 * the future from [classifyAsync]; fail the future rather than cancel it when classification fails,
 * since a canceled future raises `CancellationException` in [classify].
 */
@AdkJavaInteropApi
@ExperimentalClassifierApi
abstract class BaseFutureClassifier(final override val name: String) : Classifier {

  /**
   * Asynchronous counterpart of [classify]; return the future promptly and run any blocking work
   * inside it rather than on the calling coroutine thread.
   */
  protected abstract fun classifyAsync(
    request: ClassifierRequest
  ): CompletableFuture<ClassifierResponse>

  final override suspend fun classify(request: ClassifierRequest): ClassifierResponse =
    classifyAsync(request).await()
}
