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

package com.google.adk.kt.memory.appsearch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-JVM tests for the engine-free parts of the AppSearch memory adapter: the `MemoryRecord` <->
 * `GenericDocument` mapping and the [AppSearchMemoryService.fromContext] wiring. These need no
 * native Icing engine, so they run on any host.
 *
 * The real engine (put/search/session against AppSearch LocalStorage) is exercised on a device by
 * [AppSearchMemoryServiceInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class AppSearchMemoryMappingTest {

  @Test
  fun genericDocumentRoundTrip_allFields_preservesRecord() {
    val record =
      MemoryRecord(
        namespace = "app-1/user-1",
        id = "doc-1",
        text = "The Berlin trip was wonderful",
        author = "user",
        timestamp = "2026-07-14T00:00:00Z",
        entryId = "m-1",
        contentJson = """{"parts":[{"text":"hi"}]}""",
        customMetadataJson = """{"topic":"travel"}""",
      )

    assertThat(record.toGenericDocument().toMemoryRecord()).isEqualTo(record)
  }

  @Test
  fun genericDocumentRoundTrip_nullOptionalFields_staysNull() {
    val record =
      MemoryRecord(
        namespace = "app-1/user-1",
        id = "doc-2",
        text = "Paris in the spring",
        author = null,
        timestamp = null,
        entryId = null,
        contentJson = "{}",
        customMetadataJson = "{}",
      )

    val roundTripped = record.toGenericDocument().toMemoryRecord()

    assertThat(roundTripped.author).isNull()
    assertThat(roundTripped.timestamp).isNull()
    assertThat(roundTripped.entryId).isNull()
    assertThat(roundTripped).isEqualTo(record)
  }

  @Test
  fun fromContext_returnsService() {
    val context: Context = ApplicationProvider.getApplicationContext()

    assertThat(AppSearchMemoryService.fromContext(context)).isNotNull()
  }
}
