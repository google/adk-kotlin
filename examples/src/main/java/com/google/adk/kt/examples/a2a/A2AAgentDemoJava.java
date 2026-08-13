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

package com.google.adk.kt.examples.a2a;

import com.google.adk.kt.a2a.jvm.A2AAgentKt;
import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.interop.Coroutines;
import java.util.List;
import java.util.Objects;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;

/**
 * Example agent demonstrating how to use {@code A2AAgent} to communicate with a remote
 * A2A-compliant agent on the JVM.
 *
 * <p>This demo showcases:
 *
 * <ol>
 *   <li>Auto-fetching the remote agent's {@code AgentCard} from its {@code
 *       /.well-known/agent-card.json} endpoint (the common case). To supply a pre-built card
 *       instead, use the {@code A2AAgent(name, agentCard)} overload.
 *   <li>Talking to the agent over the JSON-RPC transport backed by {@code JdkA2AHttpClient}, the
 *       JVM HTTP client. The factory injects the transport explicitly rather than relying on SDK
 *       ServiceLoader auto-resolution.
 *   <li>Wrapping the remote agent as a standard ADK {@code com.google.adk.kt.agents.Agent}.
 * </ol>
 */
public final class A2AAgentDemoJava {

  public static final BaseAgent rootAgent = createRootAgent();

  private static BaseAgent createRootAgent() {
    String agentUrl =
        Objects.requireNonNullElse(System.getenv("A2A_AGENT_URL"), "http://localhost:8080/a2a");
    String agentName = Objects.requireNonNullElse(System.getenv("A2A_AGENT_NAME"), "remote-agent");
    // Streaming follows the run's RunConfig.streamingMode (defaults to NONE -> `message/send`).
    return Coroutines.await(
        c ->
            A2AAgentKt.A2AAgent(
                agentName,
                agentUrl,
                new JdkA2AHttpClient(),
                /* description= */ null,
                List.of(),
                List.of(),
                List.of(),
                c));
  }

  private A2AAgentDemoJava() {}
}
