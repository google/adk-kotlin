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

package com.google.adk.kt.webserver;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.kt.apps.App;
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Workflow;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Starts {@link AdkApiServer} from plain Java, which compiles only against its Java surface. */
@RunWith(JUnit4.class)
public final class AdkApiServerJavaInteropTest {

  @Test
  public void start_withoutArguments_returnsAndServesTheApp() throws Exception {
    // Arrange
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Workflow workflow =
        Workflow.builder().name("app").edges(g -> g.fromStart().then(new JoinNode("join"))).build();
    AdkApiServer server =
        new AdkApiServer(
            AdkServerConfig.inMemory(
                App.builder().appName("app").rootNode(workflow).build(), port));

    try {
      // Act
      CompletableFuture.runAsync(server::start).get(30, TimeUnit.SECONDS);
      HttpResponse<String> apps =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/list-apps"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());

      // Assert
      assertThat(apps.statusCode()).isEqualTo(200);
      assertThat(apps.body()).isEqualTo("[\"app\"]");
    } finally {
      server.stop();
    }
  }
}
