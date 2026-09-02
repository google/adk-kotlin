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

package com.google.adk.kt.telemetry

import com.google.common.truth.Truth.assertThat
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers the parsing of `OTEL_RESOURCE_ATTRIBUTES` and `OTEL_SERVICE_NAME`.
 *
 * Registering providers is not covered: `buildAndRegisterGlobal` is a one-shot process-global, so a
 * test that called it would decide the result of every other test sharing the JVM.
 */
@RunWith(JUnit4::class)
class OtelSetupTest {

  private fun Attributes.string(key: String): String? = get(AttributeKey.stringKey(key))

  @Test
  fun otelEnvironmentAttributes_readsEveryPairAndTrimsAroundThem() {
    val attributes =
      otelEnvironmentAttributes(
        resourceAttributes = "service.namespace=shop , deployment.environment=staging",
        serviceName = null,
      )

    assertThat(attributes.string("service.namespace")).isEqualTo("shop")
    assertThat(attributes.string("deployment.environment")).isEqualTo("staging")
  }

  @Test
  fun otelEnvironmentAttributes_percentDecodesValues() {
    val attributes =
      otelEnvironmentAttributes(resourceAttributes = "host.name=web%2D01%2Eeu", serviceName = null)

    assertThat(attributes.string("host.name")).isEqualTo("web-01.eu")
  }

  @Test
  fun otelEnvironmentAttributes_keepsALiteralPlus() {
    val attributes =
      otelEnvironmentAttributes(resourceAttributes = "build.tag=1.2+rc1", serviceName = null)

    assertThat(attributes.string("build.tag")).isEqualTo("1.2+rc1")
  }

  @Test
  fun otelEnvironmentAttributes_keepsAValueWhoseEscapesAreMalformed() {
    val attributes =
      otelEnvironmentAttributes(resourceAttributes = "build.tag=100%rc", serviceName = null)

    assertThat(attributes.string("build.tag")).isEqualTo("100%rc")
  }

  @Test
  fun otelEnvironmentAttributes_keepsAValueContainingAnEqualsSign() {
    val attributes =
      otelEnvironmentAttributes(resourceAttributes = "filter=a=b", serviceName = null)

    assertThat(attributes.string("filter")).isEqualTo("a=b")
  }

  @Test
  fun otelEnvironmentAttributes_dropsOnlyTheEntryWithNoEqualsSign() {
    val attributes =
      otelEnvironmentAttributes(
        resourceAttributes = "service.namespace=shop,stray,deployment.environment=staging",
        serviceName = null,
      )

    assertThat(attributes.string("service.namespace")).isEqualTo("shop")
    assertThat(attributes.string("deployment.environment")).isEqualTo("staging")
    assertThat(attributes.size()).isEqualTo(2)
  }

  @Test
  fun otelEnvironmentAttributes_ignoresBlankEntriesAndAnUnsetVariable() {
    assertThat(otelEnvironmentAttributes(resourceAttributes = null, serviceName = null).isEmpty())
      .isTrue()
    assertThat(otelEnvironmentAttributes(resourceAttributes = ",, ,", serviceName = null).isEmpty())
      .isTrue()
  }

  @Test
  fun otelEnvironmentAttributes_serviceNameBeatsTheOneInTheAttributeList() {
    val attributes =
      otelEnvironmentAttributes(
        resourceAttributes = "service.name=from-attributes",
        serviceName = "from-service-name",
      )

    assertThat(attributes.string("service.name")).isEqualTo("from-service-name")
  }

  @Test
  fun otelEnvironmentAttributes_anEmptyServiceNameLeavesTheAttributeListAlone() {
    val attributes =
      otelEnvironmentAttributes(
        resourceAttributes = "service.name=from-attributes",
        serviceName = "",
      )

    assertThat(attributes.string("service.name")).isEqualTo("from-attributes")
  }
}
