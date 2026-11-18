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
import static org.junit.Assert.assertThrows;

import com.google.adk.kt.agents.Context;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.types.Content;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.reactivestreams.Publisher;

/**
 * Sets and reads {@link RetryConfig} from plain Java, which compiles only against its Java surface.
 */
@RunWith(JUnit4.class)
public final class RetryConfigJavaInteropTest {

  @Test
  public void exceptionJavaClasses_varargs_readBackInOrder() {
    // Act
    RetryConfig config =
        RetryConfig.builder()
            .exceptionJavaClasses(IOException.class, TimeoutException.class)
            .build();

    // Assert
    assertThat(config.exceptionJavaClasses())
        .containsExactly(IOException.class, TimeoutException.class)
        .inOrder();
  }

  @Test
  public void exceptionJavaClasses_list_readBackInOrder() {
    // Act
    RetryConfig config =
        RetryConfig.builder()
            .exceptionJavaClasses(ImmutableList.of(IOException.class, TimeoutException.class))
            .build();

    // Assert
    assertThat(config.exceptionJavaClasses())
        .containsExactly(IOException.class, TimeoutException.class)
        .inOrder();
  }

  @Test
  public void exceptionJavaClasses_unset_isNull() {
    // Act
    RetryConfig config = RetryConfig.builder().build();

    // Assert
    assertThat(config.exceptionJavaClasses()).isNull();
  }

  @Test
  public void exceptionJavaClasses_null_unsetsTheClasses() {
    // Act
    RetryConfig config =
        RetryConfig.builder()
            .exceptionJavaClasses(IOException.class)
            .exceptionJavaClasses(null)
            .build();

    // Assert
    assertThat(config.exceptionJavaClasses()).isNull();
  }

  @Test
  public void exceptionJavaClasses_nullElement_isRejected() {
    // Arrange
    RetryConfig.Builder builder = RetryConfig.builder();

    // Act and assert
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.exceptionJavaClasses(Arrays.asList(IOException.class, null)));
  }

  @Test
  public void exceptionJavaClasses_superclassOfTheFailure_retriesIt() {
    // Act
    int attempts = attemptsOfAFailingNodeRetriedOn(RuntimeException.class);

    // Assert
    assertThat(attempts).isEqualTo(3);
  }

  @Test
  public void exceptionJavaClasses_unrelatedClass_doesNotRetry() {
    // Act
    int attempts = attemptsOfAFailingNodeRetriedOn(IllegalStateException.class);

    // Assert
    assertThat(attempts).isEqualTo(1);
  }

  /**
   * Runs a workflow whose only node fails with an {@link UncheckedIOException} on every attempt,
   * allowing 3 attempts on {@code retryOn}, and returns how many attempts ran.
   */
  private static int attemptsOfAFailingNodeRetriedOn(Class<? extends Exception> retryOn) {
    AtomicInteger attempts = new AtomicInteger();
    RetryConfig retryConfig =
        RetryConfig.builder()
            .maxAttempts(3)
            .initialDelayMillis(0)
            .exceptionJavaClasses(retryOn)
            .build();
    Node failing =
        new BasePublisherNode("failing", NodeConfig.builder().retryConfig(retryConfig).build()) {
          @Override
          protected Publisher<?> runNodeJava(Context context, Object nodeInput) {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("failed"));
          }
        };
    Workflow workflow =
        Workflow.builder().name("wf").edges(g -> g.fromStart().then(failing)).build();
    try (InMemoryRunner runner =
        InMemoryRunner.builder()
            .app(App.builder().appName("wf").rootNode(workflow).build())
            .build()) {
      var events =
          runner.runAsync("user", "session", null, Content.fromText("user", "go"), null, null);
      assertThrows(UncheckedIOException.class, () -> AsyncJavaHelpers.collect(events));
    }
    return attempts.get();
  }
}
