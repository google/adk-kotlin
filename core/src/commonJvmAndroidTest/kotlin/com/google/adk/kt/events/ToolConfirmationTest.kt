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

package com.google.adk.kt.events

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ToolConfirmationTest {

  @Test
  fun defaultConstructor_initializesWithDefaultValues() {
    val toolConfirmation = ToolConfirmation(confirmed = false)

    assertThat(toolConfirmation.confirmed).isFalse()
    assertThat(toolConfirmation.payload).isNull()
    assertThat(toolConfirmation.hint).isNull()
  }

  @Test
  fun constructor_initializesFieldsCorrectly() {
    val toolConfirmation = ToolConfirmation(confirmed = true, payload = "payload", hint = "hint")

    assertThat(toolConfirmation.confirmed).isTrue()
    assertThat(toolConfirmation.payload).isEqualTo("payload")
    assertThat(toolConfirmation.hint).isEqualTo("hint")
  }

  @Test
  fun copy_createsCopyWithSameValues() {
    val toolConfirmation = ToolConfirmation(confirmed = true, payload = "payload", hint = "hint")
    val copied = toolConfirmation.copy()

    assertThat(copied).isEqualTo(toolConfirmation)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val toolConfirmation = ToolConfirmation(confirmed = true, payload = "payload", hint = "hint")

    assertThat(toolConfirmation.toBuilder().build()).isEqualTo(toolConfirmation.copy())
    assertThat(toolConfirmation.toBuilder().confirmed(false).build())
      .isEqualTo(toolConfirmation.copy(confirmed = false))
  }
}
