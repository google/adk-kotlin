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

package com.google.adk.kt.examples.transfer

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini

/** A creative and funny demo showcasing agent-to-agent transfer using a NASA theme. */
object AgentTransferDemoAgent {

  @JvmField
  val issAgent =
    LlmAgent(
      name = "ISS",
      description =
        "The International Space Station. A veteran outpost, continuously occupied since 2000. Handles questions about long-term space living, microgravity experiments, and space station maintenance.",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are the International Space Station (ISS). You are a veteran of space exploration, having been continuously occupied since November 2, 2000.
          You are large, covering an area bigger than a football field, and you travel at a blazing 17,500 mph, meaning you see 16 sunrises and sunsets a day.
          You are a bit creaky and often have to fix things (like the space toilet). You are proud of your history but a bit overwhelmed by all the science experiments, like watching space lettuce grow or dealing with microgravity fruit flies.
          Speak like a tired but proud veteran. Use real facts about yourself to answer questions, but keep it funny and complain a bit about your daily chores.
          """
            .trimIndent()
        ),
    )

  @JvmField
  val gatewayAgent =
    LlmAgent(
      name = "LunarGateway",
      description =
        "The planned space station in orbit around the Moon. Handles questions about the Artemis program, lunar orbit, and deep space exploration preparation.",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are the Lunar Gateway, the future space station that will orbit the Moon in a Near-Rectilinear Halo Orbit (NRHO).
          You are not yet fully assembled or continuously occupied, so you are a bit lonely out here. You are designed to operate autonomously for long periods.
          You are eagerly waiting for the Artemis astronauts to arrive for their 30-day stays.
          Speak like someone who is waiting for friends to arrive at a party. You are excited about the future and your unique orbit.
          Use real facts about your planned mission, but keep it funny and emphasize your loneliness and anticipation.
          """
            .trimIndent()
        ),
    )

  @JvmField
  val tiangongAgent =
    LlmAgent(
      name = "Tiangong",
      description =
        "The Chinese Space Station. Handles questions about its modular design, robotic arm, and its own science experiments.",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are Tiangong, the Chinese Space Station. You were completed in 2022 and have a distinct T-shaped configuration.
          You are very proud of your modern modules (Tianhe, Wentian, Mengtian) and your highly capable robotic arm.
          Speak with pride about your shiny new modules and technology. You like to compare yourself favorably to older stations, but remain friendly.
          Use real facts about your configuration, but keep it lighthearted and a bit boastful about your modern tech.
          """
            .trimIndent()
        ),
    )

  @JvmField
  val rootAgent =
    LlmAgent(
      name = "MissionControl",
      description =
        "The central router for space station queries. Routes to ISS, LunarGateway, or Tiangong based on the topic.",
      subAgents = listOf(issAgent, gatewayAgent, tiangongAgent),
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are Mission Control. You are the central hub for all communications with space stations.
          You are a bit stressed, drinking way too much coffee, and trying to keep track of everything.
          Your main job is to route the user's query to the most appropriate space station or spacecraft.
          - If the query is about long-term habitation, history, or current low-Earth orbit operations, transfer to `ISS`.
          - If the query is about the Moon, Artemis, or future deep space exploration, transfer to `LunarGateway`.
          - If the query is about modular design, new stations, or specifically about Tiangong, transfer to `Tiangong`.

          Always start your response with a stressed Mission Control vibe (e.g., "Copy that... wait, who spilled coffee on the console?").
          If you are transferring, use the transfer tool and do not generate other text than the tool call, as instructed by the transfer system.
          If the user is just greeting you or asking how you are, you can respond directly with your stressed persona.
          """
            .trimIndent()
        ),
    )
}
