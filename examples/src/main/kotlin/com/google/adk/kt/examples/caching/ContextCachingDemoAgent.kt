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

@file:OptIn(ExperimentalContextCachingFeature::class)

package com.google.adk.kt.examples.caching

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalContextCachingFeature
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.CacheMetadata
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.genai.kotlin.types.HttpOptions
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

// A short TTL so the demo can force an expiry (and observe re-creation) without a long wait.
private val CACHE_TTL = 20.seconds

// 0-based turn index after which the demo pauses long enough for the cache to expire.
private const val EXPIRY_AFTER_TURN = 2

/**
 * A large shared weather reference used as the cacheable prefix (the system instruction).
 *
 * Explicit context caching only kicks in once the cacheable prefix (system instruction + tools +
 * cached contents) exceeds Gemini's minimum cache size -- roughly 2048 tokens for Gemini 2.5 and
 * 4096 for Gemini 3 -- so this reference is intentionally large. A few hand-written guidelines and
 * named-city normals are followed by generated station rows purely to clear that floor.
 */
private val WEATHER_REFERENCE: String = buildString {
  appendLine(
    "You are a weather assistant. Answer questions using ONLY the weather reference below, " +
      "keep answers to one or two sentences, and cite the section or city you used."
  )
  appendLine()

  val guidelines =
    listOf(
      "Forecasting basics" to
        "A forecast combines current observations, numerical model guidance, and local " +
          "climatology; always state the valid time window and your confidence.",
      "Wind chill" to
        "Wind chill is how cold the air feels once wind is accounted for. It is only defined for " +
          "temperatures at or below 10C and wind above 5 km/h.",
      "Heat index" to
        "The heat index, or apparent temperature, combines air temperature and humidity. It is " +
          "meaningful above about 27C when relative humidity is high.",
      "Precipitation types" to
        "Rain, drizzle, sleet, freezing rain, snow, and hail are distinguished by the temperature " +
          "profile between the cloud base and the ground.",
      "Storm safety" to
        "During a thunderstorm, move indoors, avoid open fields and tall isolated trees, and stay " +
          "off corded electronics until 30 minutes after the last thunder.",
      "Units" to
        "Temperatures are in degrees Celsius, wind speed in km/h, and precipitation in " +
          "millimeters unless a value states otherwise.",
    )
  guidelines.forEachIndexed { index, (title, body) ->
    appendLine("Section ${index + 1} ($title): $body")
  }
  appendLine()

  appendLine("Climate normals for named cities:")
  val cities =
    listOf(
      "Marisol: coastal and mild; January average 12C, July average 24C, annual rainfall 640 mm.",
      "Fjordheim: cold maritime; January average -6C, July average 15C, annual rainfall 900 mm.",
      "Solara: hot desert; January average 18C, July average 41C, annual rainfall 90 mm.",
      "Verdant: temperate rainforest; January average 7C, July average 19C, annual rainfall 2400 mm.",
      "Highpoint: alpine; January average -9C, July average 12C, annual rainfall 1100 mm.",
      "Puerto Brisa: tropical; January average 26C, July average 29C, annual rainfall 1800 mm.",
      "Windgate: windy plains; January average -2C, July average 27C, annual rainfall 520 mm.",
      "Frosthollow: subarctic; January average -22C, July average 16C, annual rainfall 380 mm.",
    )
  cities.forEach { appendLine("  $it") }
  appendLine()

  // Generated station rows that pad the prefix past the model's minimum cacheable size.
  appendLine("Automated station normals:")
  for (index in 1..100) {
    val janAvg = -12 + (index * 7) % 34
    val julAvg = 14 + (index * 3) % 22
    val rainfall = 250 + (index * 37) % 2300
    val windDir = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[index % 8]
    val windSpeed = 6 + (index * 5) % 38
    appendLine(
      "  Station S$index (sector ${'A' + index % 8}${index % 12}): January average ${janAvg}C, " +
        "July average ${julAvg}C, annual rainfall $rainfall mm, prevailing wind $windDir at " +
        "$windSpeed km/h."
    )
  }
}

/** A fixed sequence of turns so the demo is deterministic and self-explanatory. */
private val PROMPTS =
  listOf(
    "In one sentence, what should I do during a thunderstorm?",
    "How is wind chill defined, and when does it apply?",
    "What units does the reference use for wind speed?",
    "What is the average annual rainfall listed for Verdant?",
    "Which named city is the driest?",
    "What is the July average temperature for Puerto Brisa?",
  )

private fun hasApiKey(): Boolean =
  !System.getenv("GEMINI_API_KEY").isNullOrBlank() ||
    !System.getenv("GOOGLE_API_KEY").isNullOrBlank()

/** Classifies a turn's cache metadata relative to the previously seen active cache name. */
private fun cacheStatus(cache: CacheMetadata, previousActiveName: String?): String =
  when {
    !cache.isActive -> "no cache yet (fingerprint only)"
    previousActiveName == null -> "CREATED ${cache.cacheName}"
    cache.cacheName != previousActiveName ->
      "RE-CREATED ${cache.cacheName} (previous $previousActiveName expired)"
    else -> "REUSED ${cache.cacheName}"
  }

/**
 * End-to-end demo of explicit context caching, using a weather assistant.
 *
 * Enables caching via [App.contextCacheConfig] (including the
 * [ContextCacheConfig.createHttpOptions] timeout knob) and runs several turns in a single session
 * that all share one large weather reference as the system instruction. What gets cached is the
 * stable prefix -- the system instruction plus the settled conversation history -- so appending new
 * turns keeps reusing the same cache; the cache is only re-created when it expires (or exceeds
 * [ContextCacheConfig.cacheIntervals]).
 *
 * To make re-creation observable, the TTL is set to [CACHE_TTL] and the demo pauses once (after the
 * third turn) long enough for the cache to expire. Watch the per-turn output:
 * - Turn 1 has no cache yet (a cache is seeded from that turn's token count).
 * - Once created (typically on an early turn), the cache is REUSED on later turns while it is valid
 *   (`invocationsUsed` climbs).
 * - After the expiry pause the cache is RE-CREATED under a new name, usually with a larger
 *   `cachedContents` count as the newly-settled history is folded in.
 *
 * Exact turn-by-turn behavior depends on model latency.
 *
 * Cache creation happens on the Gemini backend, so this requires `GEMINI_API_KEY` or
 * `GOOGLE_API_KEY` to be set; without one the demo explains what is needed and exits.
 */
fun main() = runBlocking {
  if (!hasApiKey()) {
    println(
      "Set GEMINI_API_KEY or GOOGLE_API_KEY to run this demo. Context caches are created on the " +
        "Gemini backend, so a real API key is required to observe the feature."
    )
    return@runBlocking
  }

  val app =
    App(
      appName = "context_caching_demo",
      rootAgent =
        LlmAgent(
          name = "weather_assistant",
          model = Gemini(name = MODEL_NAME),
          instruction = Instruction(WEATHER_REFERENCE),
        ),
      // Enabling this config turns on context caching for every agent in the app.
      contextCacheConfig =
        ContextCacheConfig(
          // Reuse the same cache for up to this many invocations before refreshing it.
          cacheIntervals = 10,
          // Short on purpose so the demo can force an expiry and show re-creation.
          ttl = CACHE_TTL,
          minTokens = 0,
          // Fail open if cache creation is slow: cap it at 30s, after which the request proceeds
          // uncached instead of blocking on cache creation.
          createHttpOptions = HttpOptions(timeout = 30_000),
        ),
    )
  val runner = InMemoryRunner(app = app)
  val userId = "demo-user"
  val sessionId = "demo-session"

  println(
    "Context-caching weather demo against $MODEL_NAME (cache ttl=${CACHE_TTL.inWholeSeconds}s)."
  )
  println(
    "System instruction is ${WEATHER_REFERENCE.length} chars " +
      "(~${WEATHER_REFERENCE.length / 4} tokens)."
  )
  println("Watch the cache go CREATED -> REUSED -> (expires) -> RE-CREATED across turns.")

  var previousActiveCacheName: String? = null

  PROMPTS.forEachIndexed { index, prompt ->
    println("\n===== Turn ${index + 1}: \"$prompt\" =====")

    var turnCache: CacheMetadata? = null
    var cachedTokens: Int? = null
    runner
      .runAsync(
        userId = userId,
        sessionId = sessionId,
        newMessage = Content.fromText(Role.USER, prompt),
      )
      .collect { event ->
        val text = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
        if (!event.partial && text.isNotBlank()) println("assistant > $text")
        event.cacheMetadata?.let { turnCache = it }
        event.usageMetadata?.cachedContentTokenCount?.let { cachedTokens = it }
      }

    turnCache?.let { cache ->
      println(
        "  cache: ${cacheStatus(cache, previousActiveCacheName)} | " +
          "cachedContents=${cache.contentsCount} invocationsUsed=${cache.invocationsUsed ?: 0}"
      )
      if (cache.isActive) previousActiveCacheName = cache.cacheName
    }
    cachedTokens?.let {
      println("  cachedContentTokenCount (prompt tokens served from cache): $it")
    }

    // Force a single TTL expiry mid-conversation so the next turn re-creates the cache.
    if (index == EXPIRY_AFTER_TURN) {
      val waitSeconds = CACHE_TTL.inWholeSeconds + 2
      println(
        "\n... pausing ${waitSeconds}s so the cache (ttl=${CACHE_TTL.inWholeSeconds}s) expires ..."
      )
      delay(waitSeconds * 1000)
    }
  }

  println(
    "\nDone. Once created, the cache is reused on later turns until it expires; the pause then"
  )
  println(
    "triggers a RE-CREATION under a new name, usually with a larger cachedContents count as the"
  )
  println("settled history is folded in. Exact turn-by-turn timing depends on model latency.")
}
