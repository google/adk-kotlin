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

package com.google.adk.kt.mlkit

import com.google.adk.kt.logging.LoggerFactory
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel

/**
 * Helper functions for initializing, downloading, and checking the status of ML Kit's Generative
 * Models.
 */
object GenerativeModelHelpers {
  private val logger = LoggerFactory.getLogger(GenerativeModelHelpers::class)

  /**
   * Initializes a [GenerativeModel] instance with the default configuration.
   *
   * This function will download the model if it is not already available and call
   * [GenerativeModel.warmup] on it.
   *
   * @return The [GenerativeModel] instance.
   */
  suspend fun initGenerativeModel(): GenerativeModel {
    return Generation.getClient().also { initialize(it) }
  }

  /**
   * Initializes a [GenerativeModel] instance with the given [config].
   *
   * This function will download the model if it is not already available and call
   * [GenerativeModel.warmup] on it.
   *
   * @param config The [GenerationConfig] to use for initialization.
   * @return The [GenerativeModel] instance.
   */
  suspend fun initGenerativeModel(config: GenerationConfig): GenerativeModel {
    return Generation.getClient(config).also { initialize(it) }
  }

  /**
   * A convenience function to initialize a [GenerativeModel] instance with a configuration block.
   *
   * This function will download the model if it is not already available and call
   * [GenerativeModel.warmup] on it.
   *
   * @param block The block to configure the [GenerationConfig].
   * @return The [GenerativeModel] instance.
   */
  suspend fun initGenerativeModel(block: GenerationConfig.Builder.() -> Unit): GenerativeModel {
    return initGenerativeModel(GenerationConfig.builder().apply(block).build())
  }

  /**
   * Checks the status of the given [model] and throws an exception if the model is not available.
   *
   * If the model is downloadable, this function will initiate the download. Once the function
   * returns, the model will be available for use.
   *
   * @param model The [GenerativeModel] to handle the status of.
   */
  internal suspend fun handleModelStatus(model: GenerativeModel) {
    val status = model.checkStatus()
    when (status) {
      FeatureStatus.UNAVAILABLE -> {
        throw IllegalStateException(
          "Gemini Nano is not supported on this device or device hasn't fetched the latest configuration to support it"
        )
      }

      FeatureStatus.DOWNLOADABLE -> {
        // Gemini Nano can be downloaded on this device, let's initiate the download.
        model.download().collect { status ->
          when (status) {
            is DownloadStatus.DownloadStarted ->
              logger.debug { "starting download for Gemini Nano" }

            is DownloadStatus.DownloadProgress ->
              logger.debug { "Gemini Nano ${status.totalBytesDownloaded} bytes downloaded" }

            DownloadStatus.DownloadCompleted -> {
              logger.debug { "Gemini Nano download complete" }
            }

            is DownloadStatus.DownloadFailed -> {
              throw IllegalStateException(
                "Gemini Nano download failed: ${status.e.message}",
                status.e,
              )
            }
          }
        }
      }

      FeatureStatus.DOWNLOADING -> {
        // Gemini Nano is currently being downloaded. This indicates that the model is
        // being initialized elsewhere. Let's refuse to initialize the model here.
        throw IllegalStateException("Gemini Nano is currently being downloaded")
      }

      FeatureStatus.AVAILABLE -> {
        logger.debug { "Gemini Nano is available" }
        // Gemini Nano currently downloaded and available to use on this device
      }
    }
  }

  private suspend fun initialize(model: GenerativeModel) {
    handleModelStatus(model)
    model.warmup()
  }
}
