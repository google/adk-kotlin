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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedStateScopeTest {

  @Test
  fun changedBy_sessionAndTempKeysOnly_isEmpty() {
    val changed = SharedStateScope.changedBy(KEY, listOf("count", "temp:draft"))

    assertTrue(changed.isEmpty())
  }

  @Test
  fun changedBy_userKey_isUsersScope() {
    val changed = SharedStateScope.changedBy(KEY, listOf("count", "user:theme"))

    assertEquals(setOf<SharedStateScope>(SharedStateScope.User(APP, USER)), changed)
  }

  @Test
  fun changedBy_appKey_isAppsScope() {
    val changed = SharedStateScope.changedBy(KEY, listOf("app:flag"))

    assertEquals(setOf<SharedStateScope>(SharedStateScope.App(APP)), changed)
  }

  @Test
  fun changedBy_userAndAppKeys_isBothScopes() {
    val changed = SharedStateScope.changedBy(KEY, listOf("user:theme", "app:flag", "user:lang"))

    assertEquals(setOf(SharedStateScope.User(APP, USER), SharedStateScope.App(APP)), changed)
  }

  @Test
  fun seenBy_isUsersAndAppsScopes() {
    val seen = SharedStateScope.seenBy(APP, USER)

    assertEquals(setOf(SharedStateScope.User(APP, USER), SharedStateScope.App(APP)), seen)
  }

  @Test
  fun isSeenBy_userScope_onlyByThatUsersSessions() {
    val scope = SharedStateScope.User(APP, USER)

    assertTrue(scope.isSeenBy(KEY))
    assertTrue(scope.isSeenBy(KEY.copy(id = "other")))
    assertFalse(scope.isSeenBy(SessionKey(APP, "other-user", SESSION)))
    assertFalse(scope.isSeenBy(SessionKey("other-app", USER, SESSION)))
  }

  @Test
  fun isSeenBy_appScope_byEverySessionOfThatApp() {
    val scope = SharedStateScope.App(APP)

    assertTrue(scope.isSeenBy(KEY))
    assertTrue(scope.isSeenBy(SessionKey(APP, "other-user", SESSION)))
    assertFalse(scope.isSeenBy(SessionKey("other-app", USER, SESSION)))
  }

  @Test
  fun isSeenBy_agreesWithSeenBy() {
    val keys =
      listOf(KEY, SessionKey(APP, "other-user", SESSION), SessionKey("other-app", USER, SESSION))
    val scopes = keys.flatMap { SharedStateScope.seenBy(it.appName, it.userId) }.toSet()

    for (key in keys) {
      for (scope in scopes) {
        assertEquals(
          scope in SharedStateScope.seenBy(key.appName, key.userId),
          scope.isSeenBy(key),
          "$scope seen by $key",
        )
      }
    }
  }

  private companion object {
    const val APP = "app"
    const val USER = "user"
    const val SESSION = "session"
    val KEY = SessionKey(APP, USER, SESSION)
  }
}
