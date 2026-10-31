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

package com.google.adk.kt.classifiers

import com.google.adk.kt.annotations.ExperimentalClassifierApi

/**
 * A decision model that answers typed questions with probability distributions.
 *
 * Unlike a [Model][com.google.adk.kt.models.Model], a classifier does not generate text, stream, or
 * call tools. Implementations are stateless and safe for concurrent calls; context from earlier
 * turns must be included in `input`.
 */
@ExperimentalClassifierApi
interface Classifier {
  /** The provider's model name, such as `"jev-latest"` or `"gemini-3.8-flash"`. */
  val name: String

  /**
   * Answers every question in [questions] against [input] and returns one [Answer] per question
   * name.
   *
   * Every implementation accepts strings and JSON-shaped maps and lists; models that support
   * multimodal input also accept a single top-level [Content][com.google.adk.kt.types.Content], and
   * others reject it with [IllegalArgumentException].
   */
  suspend fun classify(input: Any, questions: Map<String, Question>): ClassifierResponse
}
