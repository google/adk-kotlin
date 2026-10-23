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

package com.google.adk.kt.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [SessionKey]. */
class SessionKeyTest {

  @Test
  fun acceptsBlankComponents() {
    val key = SessionKey("", "", "")

    assertEquals("", key.appName)
    assertEquals("", key.userId)
    assertEquals("", key.id)
  }

  @Test
  fun acceptsNullId() {
    val key = SessionKey("app-name", "user-id", id = null)

    assertEquals("app-name", key.appName)
    assertEquals("user-id", key.userId)
    assertNull(key.id)
  }

  @Test
  fun equality_nullVsBlankId_isNotEqual() {
    val a = SessionKey("app-name", "user-id", id = null)
    val b = SessionKey("app-name", "user-id", "")

    assertNotEquals(a, b)
  }

  @Test
  fun equality_sameComponents_isEqual() {
    val a = SessionKey("app-name", "user-id", "session-id")
    val b = SessionKey("app-name", "user-id", "session-id")

    assertEquals(a, b)
  }

  @Test
  fun equality_differentAppName_isNotEqual() {
    val a = SessionKey("app-name", "user-id", "session-id")
    val b = SessionKey("other-app", "user-id", "session-id")

    assertNotEquals(a, b)
  }

  @Test
  fun equality_differentUserId_isNotEqual() {
    val a = SessionKey("app-name", "user-id", "session-id")
    val b = SessionKey("app-name", "other-user", "session-id")

    assertNotEquals(a, b)
  }

  @Test
  fun equality_differentId_isNotEqual() {
    val a = SessionKey("app-name", "user-id", "session-id")
    val b = SessionKey("app-name", "user-id", "other-session")

    assertNotEquals(a, b)
  }

  @Test
  fun equality_isWhitespaceSensitive() {
    val a = SessionKey("app-name", "user-id", "session-id")
    val b = SessionKey("app-name", "user-id", " session-id")

    assertNotEquals(a, b)
  }

  @Test
  fun hashCode_consistentWithEquals() {
    val a = SessionKey("app-name", "user-id", "session-id")
    val b = SessionKey("app-name", "user-id", "session-id")

    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun toString_includesAllComponents() {
    val rendered = SessionKey("app-name", "user-id", "session-id").toString()

    assertTrue(rendered.contains("app-name"), "expected appName in $rendered")
    assertTrue(rendered.contains("user-id"), "expected userId in $rendered")
    assertTrue(rendered.contains("session-id"), "expected id in $rendered")
  }
}
