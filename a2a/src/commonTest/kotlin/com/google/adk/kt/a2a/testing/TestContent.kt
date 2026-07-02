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
package com.google.adk.kt.a2a.testing

import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

// Mirrors core's `com.google.adk.kt.testing` content builders. Duplicated here because a KMP module
// cannot depend on another module's `commonTest` source set (see the sibling `DummyAgent`).

/** A `user`-role text [Content] (the typical user message). */
fun userMessage(text: String): Content =
  Content(role = Role.USER, parts = listOf(Part(text = text)))

/** A `model`-role text [Content] (the typical model response body). */
fun modelMessage(text: String): Content =
  Content(role = Role.MODEL, parts = listOf(Part(text = text)))
