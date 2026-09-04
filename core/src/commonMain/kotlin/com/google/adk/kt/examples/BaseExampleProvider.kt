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

package com.google.adk.kt.examples

/**
 * A source of few-shot examples.
 *
 * Few-shot examples do not have to be a fixed list written into the agent: an implementation picks
 * the examples that suit the query it is given, so it can select from a store, an index or a
 * hand-written rule.
 */
interface BaseExampleProvider {
  /**
   * Returns the examples for [query], in the order they should be shown to the model.
   *
   * @param query The query to get examples for.
   * @return The selected examples, empty if this provider has none for the query.
   */
  suspend fun getExamples(query: String): List<Example>
}
