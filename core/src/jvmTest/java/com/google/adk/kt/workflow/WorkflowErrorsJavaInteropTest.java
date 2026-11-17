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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

/** Raises and reads workflow node failures from plain Java. */
@RunWith(JUnit4.class)
public final class WorkflowErrorsJavaInteropTest {

  @Test
  public void timedOutNode_failsWithItsTimeoutInMillis() {
    // Arrange
    Node never =
        new BasePublisherNode("never", NodeConfig.builder().timeoutMillis(50L).build()) {
          @Override
          protected Publisher<?> runNodeJava(Context context, Object nodeInput) {
            return subscriber ->
                subscriber.onSubscribe(
                    new Subscription() {
                      @Override
                      public void request(long n) {}

                      @Override
                      public void cancel() {}
                    });
          }
        };

    // Act
    NodeTimeoutException error = assertThrows(NodeTimeoutException.class, () -> run(never));

    // Assert
    assertThat(error.getNodeName()).isEqualTo("never");
    assertThat(error.timeoutMillis()).isEqualTo(50L);
  }

  @Test
  public void nodeInputValidationException_messageOnly_hasNoCause() {
    // Act
    NodeInputValidationException error = new NodeInputValidationException("order has no id");

    // Assert
    assertThat(error).hasMessageThat().isEqualTo("order has no id");
    assertThat(error).hasCauseThat().isNull();
  }

  /** Runs {@code node} as the only step of a workflow. */
  private static void run(Node node) {
    Workflow workflow = Workflow.builder().name("wf").edges(g -> g.fromStart().then(node)).build();
    try (InMemoryRunner runner =
        InMemoryRunner.builder()
            .app(App.builder().appName("wf").rootNode(workflow).build())
            .build()) {
      var unused =
          AsyncJavaHelpers.collect(
              runner.runAsync("user", "session", null, Content.fromText("user", "go"), null, null));
    }
  }
}
