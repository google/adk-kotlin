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

package com.google.adk.kt.utils.mlkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.DownloadStatus.DownloadCompleted
import com.google.mlkit.genai.common.DownloadStatus.DownloadFailed
import com.google.mlkit.genai.common.DownloadStatus.DownloadProgress
import com.google.mlkit.genai.common.DownloadStatus.DownloadStarted
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class GenerativeModelHelpersTest {

  @Before fun setUp() {}

  private fun mockGenerativeModel(
    status: Int,
    downloadStatusEvents: List<DownloadStatus> = emptyList(),
  ): GenerativeModel {
    return mock<GenerativeModel>() {
      onBlocking { checkStatus() } doReturn status
      on { download() } doReturn downloadStatusEvents.asFlow()
    }
  }

  @Test
  fun handleModelStatus_unavailableFeatureStatus_throwsException() {
    val model = mockGenerativeModel(FeatureStatus.UNAVAILABLE)

    assertFailsWith<IllegalStateException> {
      runTest { GenerativeModelHelpers.handleModelStatus(model) }
    }
  }

  @Test
  fun handleModelStatus_downloadableFeatureStatus_downloadsModel() {
    val model =
      mockGenerativeModel(
        FeatureStatus.DOWNLOADABLE,
        listOf(
          DownloadStatus.DownloadStarted(100L),
          DownloadStatus.DownloadProgress(42L),
          DownloadStatus.DownloadCompleted,
        ),
      )

    runTest {
      GenerativeModelHelpers.handleModelStatus(model)
      verify(model).download()
    }
  }

  @Test
  fun handleModelStatus_downloadError_throwsException() {
    val model =
      mockGenerativeModel(
        FeatureStatus.DOWNLOADABLE,
        listOf(
          DownloadStatus.DownloadStarted(100L),
          DownloadStatus.DownloadProgress(42L),
          DownloadStatus.DownloadFailed(
            GenAiException("Download failed", null, GenAiException.ErrorCode.NOT_AVAILABLE)
          ),
        ),
      )

    assertFailsWith<IllegalStateException> {
      runTest { GenerativeModelHelpers.handleModelStatus(model) }
    }
  }

  @Test
  fun handleModelStatus_availableFeatureStatus_doesNotThrowException() {
    val model = mockGenerativeModel(FeatureStatus.AVAILABLE)
    runTest {
      GenerativeModelHelpers.handleModelStatus(model)
      verify(model, never()).download()
    }
  }

  @Test
  fun handleModelStatus_downloadingFeatureStatus_throwsException() {
    val model = mockGenerativeModel(FeatureStatus.DOWNLOADING)
    assertFailsWith<IllegalStateException> {
      runTest { GenerativeModelHelpers.handleModelStatus(model) }
    }
  }
}
