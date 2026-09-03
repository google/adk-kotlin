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

package com.google.adk.kt.examples.structural;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.InvocationContext;
import com.google.adk.kt.agents.SequentialAgent;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.events.EventActions;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherAgent;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import java.util.List;
import org.reactivestreams.Publisher;

/** A fun, nerdy demo for SequentialAgent simulating a pre-flight sequence. */
public final class SequentialAgentDemoJava {

  private static final class DummyStoryAgent extends BasePublisherAgent {
    private final String storyText;

    DummyStoryAgent(String name, String storyText) {
      super(name);
      this.storyText = storyText;
    }

    @Override
    public Publisher<Event> runAsyncImplJava(InvocationContext context) {
      return AsyncJavaHelpers.publisherOf(
          List.of(
              Event.builder()
                  .invocationId(context.getInvocationId())
                  .author(getName())
                  .content(
                      new Content(
                          /* role= */ null, List.of(Part.builder().text(storyText).build())))
                  .actions(EventActions.builder().build())
                  .build()));
    }
  }

  public static final BaseAgent rootAgent =
      SequentialAgent.builder()
          .name("PreFlightSequence")
          .subAgents(
              new DummyStoryAgent(
                  "LifeSupportCheck",
                  "Life support systems nominal. O2 levels at 21%. Pressurization complete."),
              new DummyStoryAgent(
                  "HyperdriveWarmup",
                  "Hyperdrive spin-up initiated. Spooling to 104%. No containment leaks detected."),
              new DummyStoryAgent(
                  "FlightClearance",
                  "Space Traffic Control, this is Gemini Carrier. Requesting launch clearance..."
                      + " Clearance granted! Engage!"))
          .build();

  private SequentialAgentDemoJava() {}
}
