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

package com.google.adk.kt.artifacts

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Wiring tests for the [FileArtifactService] Android factories against a real [Context]. */
@RunWith(AndroidJUnit4::class)
class FileArtifactServiceAndroidTest {

  @Test
  fun fromExternalFilesDir_savesAndLoads() = runTest {
    val service =
      FileArtifactService.fromExternalFilesDir(ApplicationProvider.getApplicationContext())
    val artifact = Part(inlineData = Blob(data = "hi".toByteArray(), mimeType = "text/plain"))

    val version = service.saveArtifact(SESSION_KEY, FILENAME, artifact)
    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertThat(version).isEqualTo(0)
    assertThat(loaded).isEqualTo(artifact)
  }

  @Test
  fun fromInternalFilesDir_savesAndLoads() = runTest {
    val service =
      FileArtifactService.fromInternalFilesDir(ApplicationProvider.getApplicationContext())
    val artifact = Part(inlineData = Blob(data = "hi".toByteArray(), mimeType = "text/plain"))

    val version = service.saveArtifact(SESSION_KEY, FILENAME, artifact)
    val loaded = service.loadArtifact(SESSION_KEY, FILENAME)

    assertThat(version).isEqualTo(0)
    assertThat(loaded).isEqualTo(artifact)
  }

  @Test
  fun fromExternalFilesDir_externalUnavailable_throws() {
    val context =
      object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getApplicationContext(): Context = this

        override fun getExternalFilesDir(type: String?): File? = null
      }

    assertThrows(IllegalStateException::class.java) {
      FileArtifactService.fromExternalFilesDir(context)
    }
  }

  private companion object {
    const val FILENAME = "test-file.txt"
    val SESSION_KEY = SessionKey(appName = "app", userId = "user", id = "session")
  }
}
