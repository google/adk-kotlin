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

import com.google.adk.kt.events.Event

/**
 * Concatenates this event's visible response text: the text of every non-thought part of its
 * content, in order, joined with no separator (empty when there is no such text).
 *
 * This mirrors how core ADK reconstructs a response's text from its parts (see
 * `LlmAgent.maybeSaveOutputToState`): thought parts are excluded, and parts are joined directly
 * (not space-separated), because they are fragments of one continuous message — a separator would
 * inject spurious spaces, including mid-word across streaming chunks. Core keeps this logic
 * private, so the examples re-expose it here as a small shared helper.
 */
fun Event.foldTextParts(): String =
  content?.parts.orEmpty().filter { it.thought != true }.mapNotNull { it.text }.joinToString("")
