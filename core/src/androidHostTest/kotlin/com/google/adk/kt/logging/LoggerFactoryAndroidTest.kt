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

package com.google.adk.kt.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoggerFactoryAndroidTest {
  @Test
  fun getLogger_namedClass_usesTheSimpleName() {
    val logger = LoggerFactory.getLogger(LoggerFactoryAndroidTest::class)

    assertEquals("LoggerFactoryAndroidTest", logger.name)
  }

  @Test
  fun getLogger_anonymousClass_usesUnknown() {
    val anonymous = object {}

    val logger = LoggerFactory.getLogger(anonymous::class)

    assertEquals("Unknown", logger.name)
  }
}
