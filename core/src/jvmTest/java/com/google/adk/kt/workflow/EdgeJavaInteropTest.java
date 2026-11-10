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

/** Builds edges from Java through the overloads {@code @JvmOverloads} generates. */
@RunWith(JUnit4.class)
public final class EdgeJavaInteropTest {

  @Test
  public void edge_fromAndToOnly_isUnconditional() {
    // Arrange
    JoinNode join = new JoinNode("join");

    // Act
    Edge edge = new Edge(Start.INSTANCE, join);

    // Assert
    assertThat(edge.isUnconditional()).isTrue();
    assertThat(edge).isEqualTo(new Edge(Start.INSTANCE, join, (Route) null));
  }
}
