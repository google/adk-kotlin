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

package com.google.adk.kt.examples.chatcompletions;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Model;
import java.util.List;
import java.util.Map;

/**
 * Java port of the travel tools and agent that the Chat Completions demos share, with the tools
 * built by {@link ReflectiveTools}.
 */
public final class TravelToolsJava {

  /** A currency the demo converts, with its fixed demo rate. */
  public enum Currency {
    EUR(1.0),
    USD(1.1),
    GBP(0.85),
    JPY(160.0);

    private final double perEuro;

    Currency(double perEuro) {
      this.perEuro = perEuro;
    }
  }

  /** Canned travel tools, so the demos show tool calls without calling a real service. */
  public static final class Tools {
    @Tool(description = "Gets tomorrow's forecast for a city.")
    public Map<String, Object> getForecast(
        @Param(name = "city", description = "The city, e.g. 'Lisbon'") String city) {
      int high = 15 + city.length() % 12;
      return Map.of(
          "city",
          city,
          "conditions",
          city.length() % 2 == 0 ? "sunny" : "cloudy",
          "highCelsius",
          high,
          "lowCelsius",
          high - 8);
    }

    @Tool(description = "Converts an amount of money between currencies at fixed demo rates.")
    public double convertCurrency(
        @Param(name = "amount", description = "The amount to convert") double amount,
        @Param(name = "from", description = "The currency to convert from") Currency from,
        @Param(name = "to", description = "The currency to convert to") Currency to) {
      return amount / from.perEuro * to.perEuro;
    }

    @Tool(description = "Lists direct flights between two cities.")
    public List<String> findFlights(
        @Param(name = "from", description = "The departure city") String from,
        @Param(name = "to", description = "The arrival city") String to) {
      return List.of(
          from + " to " + to + " at 08:15 for 189 EUR",
          from + " to " + to + " at 17:40 for 145 EUR");
    }
  }

  /** The travel agent both demos run; only the model that serves it differs. */
  static BaseAgent travelAgent(String name, Model model) {
    Tools tools = new Tools();
    return LlmAgent.builder()
        .name(name)
        .model(model)
        .instruction(
            "You are a travel assistant. Use the tools for forecasts, prices, and flights. Keep"
                + " answers short.")
        .tools(
            ReflectiveTools.fromMethod(tools, "getForecast"),
            ReflectiveTools.fromMethod(tools, "convertCurrency"),
            ReflectiveTools.fromMethod(tools, "findFlights"))
        .build();
  }

  private TravelToolsJava() {}
}
