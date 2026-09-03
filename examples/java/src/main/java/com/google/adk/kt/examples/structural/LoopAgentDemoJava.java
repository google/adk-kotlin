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
import com.google.adk.kt.agents.LoopAgent;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.events.EventActions;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherAgent;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.reactivestreams.Publisher;

/** A fun, nerdy demo for LoopAgent simulating an RPG loot grinder. */
public final class LoopAgentDemoJava {

  private static final class MonsterFightAgent extends BasePublisherAgent {
    private final List<String> loots =
        List.of("Gold x10", "Health Potion", "Rusty Dagger", "Legendary Sword of Gemini");

    MonsterFightAgent(String name) {
      super(name);
    }

    @Override
    public Publisher<Event> runAsyncImplJava(InvocationContext context) {
      int roll = ThreadLocalRandom.current().nextInt(loots.size());
      String loot = loots.get(roll);
      boolean isLegendary = loot.equals("Legendary Sword of Gemini");

      return AsyncJavaHelpers.publisherOf(
          List.of(
              Event.builder()
                  .invocationId(context.getInvocationId())
                  .author(getName())
                  .content(
                      new Content(
                          /* role= */ null,
                          List.of(
                              Part.builder()
                                  .text("Defeated a Goblin! Found loot: " + loot)
                                  .build())))
                  .actions(EventActions.builder().escalate(isLegendary).build())
                  .build()));
    }
  }

  public static final BaseAgent rootAgent =
      LoopAgent.builder()
          .name("LootGrinder")
          .subAgents(new MonsterFightAgent("GoblinSlayer"))
          .maxIterations(10) // Prevent infinite loops if unlucky
          .build();

  private LoopAgentDemoJava() {}
}
