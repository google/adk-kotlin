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

package com.google.adk.kt.examples.litertlm

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.logging.LoggerFactory
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import java.time.InstantSource
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A service containing local tools that will be exposed to the local model. */
class LocalToolService(private val instantSource: InstantSource = InstantSource.system()) {

  /** Returns the current local date and time. */
  @Tool
  fun getCurrentTime(): Map<String, String> {
    val current = LocalDateTime.ofInstant(instantSource.instant(), ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    println(">>> LocalToolService [SYSTEM]: getCurrentTime() called -> $current")
    return mapOf("time" to current.format(formatter))
  }

  /**
   * Retrieves a weather report for the specified city.
   *
   * @param city The name of the city, e.g. "San Francisco"
   * @return A map containing a fake weather report.
   */
  @Tool
  fun getWeather(city: String): Map<String, String> {
    println(">>> LocalToolService [SYSTEM]: getWeather() called for city '$city'...")
    val mockReports =
      listOf(
        "Sunny, 75°F (24°C), with a light breeze.",
        "Rainy, 55°F (13°C), with 80% humidity.",
        "Partly cloudy, 68°F (20°C), perfect weather.",
        "Foggy, 50°F (10°C), visibility 1 mile.",
      )
    val hash = Math.abs(city.hashCode()) % mockReports.size
    return mapOf("weather" to "The current weather in $city is: ${mockReports[hash]}")
  }

  /** Calculates the sum of two integers. */
  @Tool
  fun addNumbers(
    @Param("The first number to add") a: Int,
    @Param("The second number to add") b: Int,
  ): Map<String, String> {
    println(">>> LocalToolService [SYSTEM]: addNumbers() called with a=$a, b=$b...")
    return mapOf("sum" to (a + b).toString())
  }
}

/**
 * Example agent demonstrating how to use local tools with LiteRT-LM in the Kotlin ADK.
 *
 * This agent uses [LiteRtLmModel] as its execution model and has access to local functions defined
 * in [LocalToolService] via KSP tool generation.
 */
object LiteRtLmDemoAgent {

  private val logger = LoggerFactory.getLogger(LiteRtLmModel::class)

  private val modelPath: String by lazy {
    System.getenv("LITERT_LM_MODEL_PATH")
      ?: throw IllegalStateException(
        "LITERT_LM_MODEL_PATH environment variable must be set pointing to a .litertlm file."
      )
  }

  init {
    try {
      Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
    } catch (e: Throwable) {
      logger.warn(e) { "Failed to set native min log severity for LiteRT-LM Engine." }
    }
  }

  @JvmField
  val rootAgent =
    LlmAgent(
      name = "litert_lm_agent",
      model = LiteRtLmModel.create(EngineConfig(modelPath = modelPath, backend = Backend.CPU())),
      instruction =
        Instruction(
          """
          You are a helpful assistant.
          You have access to tools for getting the current time, weather of a city, and adding two numbers.
          Please use these tools when necessary to fulfill user requests. Keep your answers concise.
          """
            .trimIndent()
        ),
      tools = LocalToolService().generatedTools(),
    )
}
