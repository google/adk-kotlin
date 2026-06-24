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
package com.google.adk.kt.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class CacheMetadataTest {

  @Test
  fun construct_fingerprintOnly_isNotActive() {
    val metadata = CacheMetadata(fingerprint = "abc123", contentsCount = 3)

    assertFalse(metadata.isActive)
    assertFalse(metadata.expireSoon)
  }

  @Test
  fun construct_activeCache_exposesProperties() {
    val metadata =
      CacheMetadata(
        fingerprint = "abc123",
        contentsCount = 3,
        cacheName = "projects/p/locations/l/cachedContents/456",
        expireTime = Clock.System.now().toEpochMilliseconds() + 1_800_000,
        invocationsUsed = 1,
        createdAt = Clock.System.now().toEpochMilliseconds(),
      )

    assertTrue(metadata.isActive)
    assertEquals(1, metadata.invocationsUsed)
  }

  @Test
  fun construct_partialActiveState_throwsIllegalArgumentException() {
    // cacheName set but expireTime/invocationsUsed null violates the active-state invariant.
    assertFailsWith<IllegalArgumentException> {
      CacheMetadata(fingerprint = "abc", contentsCount = 1, cacheName = "cache/1")
    }
  }

  @Test
  fun construct_negativeContentsCount_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      CacheMetadata(fingerprint = "abc", contentsCount = -1)
    }
  }

  @Test
  fun construct_negativeInvocationsUsed_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      CacheMetadata(
        fingerprint = "abc",
        contentsCount = 1,
        cacheName = "cache/1",
        expireTime = 1L,
        invocationsUsed = -1,
      )
    }
  }

  @Test
  fun expireSoon_pastExpiry_isTrue() {
    val metadata =
      CacheMetadata(
        fingerprint = "abc",
        contentsCount = 1,
        cacheName = "cache/1",
        expireTime = Clock.System.now().toEpochMilliseconds() - 1_000,
        invocationsUsed = 1,
      )

    assertTrue(metadata.expireSoon)
  }

  @Test
  fun expireSoon_farFutureExpiry_isFalse() {
    val metadata =
      CacheMetadata(
        fingerprint = "abc",
        contentsCount = 1,
        cacheName = "cache/1",
        expireTime = Clock.System.now().toEpochMilliseconds() + 3_600_000,
        invocationsUsed = 1,
      )

    assertFalse(metadata.expireSoon)
  }

  @Test
  fun copy_incrementInvocationsUsed_producesUpdatedCopy() {
    val metadata =
      CacheMetadata(
        fingerprint = "abc",
        contentsCount = 1,
        cacheName = "cache/1",
        expireTime = Clock.System.now().toEpochMilliseconds() + 1_000,
        invocationsUsed = 1,
      )

    val updated = metadata.copy(invocationsUsed = metadata.invocationsUsed!! + 1)

    assertEquals(2, updated.invocationsUsed)
    assertEquals(1, metadata.invocationsUsed)
  }
}
