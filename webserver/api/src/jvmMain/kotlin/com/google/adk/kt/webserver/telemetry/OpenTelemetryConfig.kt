/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.slf4j.LoggerFactory

class OpenTelemetryConfig(private val apiServerSpanExporter: ApiServerSpanExporter) {
  private val logger = LoggerFactory.getLogger(OpenTelemetryConfig::class.java)

  fun sdkTracerProvider(): SdkTracerProvider {
    logger.debug("Configuring SdkTracerProvider with ApiServerSpanExporter.")
    val resource =
      Resource.getDefault()
        .merge(
          Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "adk-web-server"))
        )

    return SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(apiServerSpanExporter))
      .setResource(resource)
      .build()
  }

  fun openTelemetrySdk(sdkTracerProvider: SdkTracerProvider): OpenTelemetry {
    logger.debug("Configuring OpenTelemetrySdk.")

    val sdkBuilder = OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider)
    return try {
      logger.debug("Attempting to register OpenTelemetry globally.")
      val otelSdk = sdkBuilder.buildAndRegisterGlobal()
      Runtime.getRuntime().addShutdownHook(Thread { otelSdk.close() })
      otelSdk
    } catch (e: IllegalStateException) {
      logger.debug("OpenTelemetry already registered globally, creating non-global instance.")
      sdkBuilder.build()
    }
  }
}
