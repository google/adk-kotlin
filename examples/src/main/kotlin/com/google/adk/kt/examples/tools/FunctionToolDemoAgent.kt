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

package com.google.adk.kt.examples.tools

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.tools.ToolContext
import kotlin.random.Random
import kotlinx.coroutines.delay

enum class TeaStatus {
  HOT,
  COLD,
  NOT_AVAILABLE,
  NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA,
}

data class Coordinates(val x: Double, val y: Double, val z: Double, val time: Double)

data class ImprobabilityReport(
  val locationName: String,
  val improbabilityLevel: Double,
  val sideEffects: List<String>,
  val teaStatus: TeaStatus,
)

/**
 * A mock service to simulate interactions with The Hitchhiker's Guide to the Galaxy. This class
 * methods are annotated with `@Tool` to expose them as tools to the LLM.
 */
class HitchhikersGuideService {

  /** Retrieves the Answer to the Ultimate Question of Life, the Universe, and Everything. */
  @Tool
  fun getAnswerToEverything(
    @Param(
      "The question to ask Deep Thought, e.g., 'What is the answer to life, the universe, and everything?'"
    )
    question: String
  ): String {
    println(">>> Deep Thought [SYSTEM]: Calculating answer for '$question'...")
    return if (question.lowercase().contains("life") && question.lowercase().contains("universe")) {
      "The answer to the Ultimate Question of Life, the Universe, and Everything is 42."
    } else {
      "I don't know the answer to that. I only know the answer to the Ultimate Question."
    }
  }

  /** Calculates the improbability of a given event. */
  @Tool
  fun calculateImprobability(
    @Param("The event to calculate the improbability for, e.g., 'A cup of tea materializing'")
    event: String,
    @Param("Desired level of improbability (optional)") level: Double? = 1.0,
  ): String {
    println(">>> Improbability Drive [SYSTEM]: Engaging for $event at level $level...")
    val improbability = Random.nextDouble() * 1000
    return "The improbability of '$event' is approximately $improbability to 1 against."
  }

  /**
   * Gets the status of the Infinite Improbability Drive at given coordinates. Demonstrates Data
   * Class parameter and suspend.
   */
  @Tool
  suspend fun getDriveStatus(
    @Param("Galactic Coordinates") coordinates: Coordinates
  ): ImprobabilityReport {
    println(
      ">>> Heart of Gold [SYSTEM]: Suspending to check drive status at ${coordinates.x}, ${coordinates.y}, ${coordinates.z}, ${coordinates.time}..."
    )
    delay(500) // Simulate Deep Thought latency
    return ImprobabilityReport(
      locationName = "Sector ZZ9 Plural Z Alpha",
      improbabilityLevel = Random.nextDouble() * 1e6,
      sideEffects = listOf("Whales and petunias materializing", "Reality alteration"),
      teaStatus = TeaStatus.NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA,
    )
  }

  /** Gets bulk guide entries. Demonstrates List parameter and Map return. */
  @Tool
  fun getBulkGuideEntries(
    @Param("List of guide entries to look up") entries: List<String>
  ): Map<String, ImprobabilityReport> {
    println(">>> The Guide [SYSTEM]: Looking up bulk entries for $entries...")
    return entries.associateWith { entry ->
      ImprobabilityReport(
        locationName = entry,
        improbabilityLevel = 42.0,
        sideEffects = emptyList(),
        teaStatus = TeaStatus.NOT_AVAILABLE,
      )
    }
  }

  /** Submits a request for tea. Demonstrates Context Injection and Enum parameters. */
  @Tool
  fun submitTeaRequest(
    context: ToolContext,
    @Param("The person requesting tea") requester: String,
    @Param("The desired status of the tea") status: TeaStatus,
  ): String {
    println(
      ">>> Nutri-Matic [SYSTEM]: Submitting $status tea request for $requester... (Call ID: ${context.functionCallId})"
    )
    return "Successfully submitted request for $status tea for $requester."
  }

  /**
   * Retrieves an entry from The Hitchhiker's Guide to the Galaxy for a specific edition. This
   * demonstrates relying on KDoc for schema extraction rather than @Param.
   *
   * @param entryName The name of the entry (e.g. 'Babel Fish')
   * @param edition The edition of the guide (e.g. 'Standard', 'Premium')
   * @return A string containing the Guide's entry.
   */
  @Tool
  fun getHistoricalGuideEntry(entryName: String, edition: String): String {
    println(">>> The Guide [SYSTEM]: Looking up entry for $entryName in the $edition edition...")
    return when (entryName.lowercase()) {
      "babel fish" ->
        "The Babel fish is small, yellow, and leech-like, and probably the oddest thing in the Universe. (Edition: $edition)"
      "vogon" -> "Vogons are one of the most unpleasant races in the Galaxy. (Edition: $edition)"
      else -> "Entry for '$entryName' not found. Mostly harmless. (Edition: $edition)"
    }
  }
}

/**
 * Example agent demonstrating how to use KSP-generated Function Tools in the Kotlin ADK.
 *
 * This example showcases:
 * 1. Defining a service class with `@Tool` annotations.
 * 2. Using the KSP-generated `generatedTools()` extension to equip an agent with these tools.
 * 3. A zero-reflection approach to function tool execution.
 */
object FunctionToolDemoAgent {

  @JvmField
  val rootAgent =
    LlmAgent(
      name = "hitchhikers_guide_bot",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      instruction =
        Instruction(
          """
          You are a helpful assistant bot themed around "The Hitchhiker's Guide to the Galaxy".
          You have access to functions simulating guide entries, improbability calculations, tea requests, and more.
          Please use and test out these various tools as requested to showcase their capabilities.
          Be witty, slightly sarcastic, and concise, in the style of the Guide. Don't Panic.
          """
            .trimIndent()
        ),
      // Note: We expect the KSP processor to generate this extension for
      // HitchhikersGuideService.
      tools = HitchhikersGuideService().generatedTools(),
    )
}
