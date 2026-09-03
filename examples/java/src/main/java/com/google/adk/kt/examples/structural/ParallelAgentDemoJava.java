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
import com.google.adk.kt.agents.ParallelAgent;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherAgent;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import java.util.List;
import org.reactivestreams.Publisher;

/**
 * A fun, quirky, family-friendly demo for ParallelAgent showcasing simultaneous actions by a team
 * of cats!
 */
public final class ParallelAgentDemoJava {

  private static final class CatAgent extends BasePublisherAgent {
    private final String actionReport;

    CatAgent(String name, String actionReport) {
      super(name);
      this.actionReport = actionReport;
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
                          /* role= */ null, List.of(Part.builder().text(actionReport).build())))
                  .build()));
    }
  }

  public static final BaseAgent rootAgent =
      ParallelAgent.builder()
          .name("CatTaskForce")
          .subAgents(
              new CatAgent("FoodDispenser", "Dispensing premium salmon kibble... Crunch crunch."),
              new CatAgent("LitterPatrol", "Scooping the box. Pristine condition achieved."),
              new CatAgent(
                  "LaserPointerOfficer", "Activating the Red Dot. Target acquired. Fast pounce!"))
          .build();

  private ParallelAgentDemoJava() {}
}
