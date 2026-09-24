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

package com.google.adk.kt.examples.chatcompletions

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.Model
import kotlinx.coroutines.delay

/** A currency the demo converts, with its fixed demo rate. */
enum class Currency(val perEuro: Double) {
  EUR(1.0),
  USD(1.1),
  GBP(0.85),
  JPY(160.0),
}

/** Tomorrow's forecast for a city. */
data class Forecast(
  val city: String,
  val conditions: String,
  val highCelsius: Int,
  val lowCelsius: Int,
)

/** Canned travel tools, so the demos show tool calls without calling a real service. */
class TravelTools {

  /** Gets tomorrow's forecast for a city. */
  @Tool
  fun getForecast(@Param("The city, e.g. 'Lisbon'") city: String): Forecast {
    val high = 15 + city.length % 12
    return Forecast(city, if (city.length % 2 == 0) "sunny" else "cloudy", high, high - 8)
  }

  /** Converts an amount of money between currencies at fixed demo rates. */
  @Tool
  fun convertCurrency(
    @Param("The amount to convert") amount: Double,
    @Param("The currency to convert from") from: Currency,
    @Param("The currency to convert to") to: Currency,
  ): Double = amount / from.perEuro * to.perEuro

  /** Lists direct flights between two cities. */
  @Tool
  suspend fun findFlights(
    @Param("The departure city") from: String,
    @Param("The arrival city") to: String,
  ): List<String> {
    // Waits as a booking service would.
    delay(200)
    return listOf("$from to $to at 08:15 for 189 EUR", "$from to $to at 17:40 for 145 EUR")
  }
}

/** The travel agent both demos run; only the model that serves it differs. */
internal fun travelAgent(name: String, model: Model): LlmAgent =
  LlmAgent(
    name = name,
    model = model,
    instruction =
      Instruction(
        "You are a travel assistant. Use the tools for forecasts, prices, and flights. Keep " +
          "answers short."
      ),
    tools = TravelTools().generatedTools(),
  )
