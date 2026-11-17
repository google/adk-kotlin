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

package com.google.adk.kt.workflow;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Reads the {@link RetryConfig} default delays from plain Java. */
@RunWith(JUnit4.class)
public final class RetryConfigDefaultsJavaInteropTest {

  @Test
  public void defaultDelayMillis_matchTheBuilderDefaults() {
    // Act
    RetryConfig defaults = RetryConfig.builder().build();

    // Assert
    assertThat(RetryConfig.DEFAULT_INITIAL_DELAY_MILLIS).isEqualTo(1_000L);
    assertThat(RetryConfig.DEFAULT_MAX_DELAY_MILLIS).isEqualTo(60_000L);
    assertThat(defaults.initialDelayMillis()).isEqualTo(RetryConfig.DEFAULT_INITIAL_DELAY_MILLIS);
    assertThat(defaults.maxDelayMillis()).isEqualTo(RetryConfig.DEFAULT_MAX_DELAY_MILLIS);
  }
}
