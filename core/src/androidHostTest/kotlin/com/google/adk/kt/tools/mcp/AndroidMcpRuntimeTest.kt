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

package com.google.adk.kt.tools.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMcpRuntimeTest {
  @Test
  fun ensureAndroidMcpRuntime_succeedsWhenSdkClassesArePresent() {
    ensureAndroidMcpRuntime()
  }

  @Test
  fun ensureAndroidMcpRuntime_explainsMissingSdkWithoutLeakingAClassNameOnly() {
    val missing = ClassNotFoundException("io.modelcontextprotocol.kotlin.sdk.client.Client")
    val error = assertFailsWith<IllegalStateException> { ensureAndroidMcpRuntime { throw missing } }

    assertEquals(ANDROID_MCP_RUNTIME_MISSING, error.message)
    assertSame(missing, error.cause)
    assertIs<ClassNotFoundException>(error.cause)
  }
}
