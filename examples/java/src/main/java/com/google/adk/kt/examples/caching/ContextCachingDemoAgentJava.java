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

package com.google.adk.kt.examples.caching;

import com.google.adk.kt.agents.ContextCacheConfig;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.ExperimentalContextCachingFeature;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.CacheMetadata;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.HttpOptions;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.List;
import org.reactivestreams.Publisher;

/**
 * End-to-end demo of explicit context caching, using a weather assistant.
 *
 * <p>Several turns share one large weather reference as the system instruction. The TTL is short
 * and the demo pauses once, so the cache can be seen expiring and being re-created.
 *
 * <p>Requires {@code GEMINI_API_KEY} or {@code GOOGLE_API_KEY}, since caches are created on the
 * Gemini backend.
 */
@ExperimentalContextCachingFeature
public final class ContextCachingDemoAgentJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";

  /** A short TTL so the demo can force an expiry (and observe re-creation) without a long wait. */
  private static final long CACHE_TTL_MILLIS = 20_000L;

  /** 0-based turn index after which the demo pauses long enough for the cache to expire. */
  private static final int EXPIRY_AFTER_TURN = 2;

  /** A fixed sequence of turns so the demo is deterministic and self-explanatory. */
  private static final List<String> PROMPTS =
      List.of(
          "In one sentence, what should I do during a thunderstorm?",
          "How is wind chill defined, and when does it apply?",
          "What units does the reference use for wind speed?",
          "What is the average annual rainfall listed for Verdant?",
          "Which named city is the driest?",
          "What is the July average temperature for Puerto Brisa?");

  public static void main(String[] args) throws InterruptedException {
    if (!hasApiKey()) {
      System.out.println(
          "Set GEMINI_API_KEY or GOOGLE_API_KEY to run this demo. Context caches are created on"
              + " the Gemini backend, so a real API key is required to observe the feature.");
      return;
    }

    String weatherReference = buildWeatherReference();

    App app =
        App.builder()
            .appName("context_caching_demo")
            .rootAgent(
                LlmAgent.builder()
                    .name("weather_assistant")
                    .model(new Gemini(MODEL_NAME))
                    .instruction(weatherReference)
                    .build())
            // Enabling this config turns on context caching for every agent in the app.
            .contextCacheConfig(
                ContextCacheConfig.builder()
                    // Reuse the same cache for up to this many invocations before refreshing it.
                    .cacheIntervals(10)
                    // Short on purpose so the demo can force an expiry and show re-creation.
                    .ttlMillis(CACHE_TTL_MILLIS)
                    .minTokens(0)
                    // Fail open if cache creation is slow: cap it at 30s, after which the request
                    // proceeds uncached instead of blocking on cache creation.
                    .createHttpOptions(HttpOptions.builder().timeoutMillis(30_000L).build())
                    .build())
            .build();

    PublisherRunner runner = PublisherRunner.inMemory(app);
    String userId = "demo-user";
    String sessionId = "demo-session";

    long ttlSeconds = CACHE_TTL_MILLIS / 1000L;
    System.out.printf(
        "Context-caching weather demo against %s (cache ttl=%ds).%n", MODEL_NAME, ttlSeconds);
    System.out.printf(
        "System instruction is %d chars (~%d tokens).%n",
        weatherReference.length(), weatherReference.length() / 4);
    System.out.println(
        "Watch the cache go CREATED -> REUSED -> (expires) -> RE-CREATED across turns.");

    String previousActiveCacheName = null;

    for (int index = 0; index < PROMPTS.size(); index++) {
      String prompt = PROMPTS.get(index);
      System.out.printf("%n===== Turn %d: \"%s\" =====%n", index + 1, prompt);

      TurnSummary summary = new TurnSummary();
      // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
      // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
      // Reactor: Flux<Event> flux = Flux.from(eventStream);
      // or block on it directly with AsyncJavaHelpers, as below.
      Publisher<Event> eventStream =
          runner.runAsync(userId, sessionId, null, Content.fromText(Role.USER, prompt));
      AsyncJavaHelpers.forEach(
          eventStream,
          event -> {
            String text = textOf(event.getContent() == null ? null : event.getContent().getParts());
            if (!event.getPartial() && !text.isBlank()) {
              System.out.println("assistant > " + text);
            }
            if (event.getCacheMetadata() != null) {
              summary.cache = event.getCacheMetadata();
            }
            if (event.getUsageMetadata() != null
                && event.getUsageMetadata().getCachedContentTokenCount() != null) {
              summary.cachedTokens = event.getUsageMetadata().getCachedContentTokenCount();
            }
          });

      if (summary.cache != null) {
        System.out.printf(
            "  cache: %s | cachedContents=%d invocationsUsed=%d%n",
            cacheStatus(summary.cache, previousActiveCacheName),
            summary.cache.getContentsCount(),
            summary.cache.getInvocationsUsed() == null ? 0 : summary.cache.getInvocationsUsed());
        if (summary.cache.isActive()) {
          previousActiveCacheName = summary.cache.getCacheName();
        }
      }
      if (summary.cachedTokens != null) {
        System.out.println(
            "  cachedContentTokenCount (prompt tokens served from cache): " + summary.cachedTokens);
      }

      // Force a single TTL expiry mid-conversation so the next turn re-creates the cache.
      if (index == EXPIRY_AFTER_TURN) {
        long waitSeconds = ttlSeconds + 2;
        System.out.printf(
            "%n... pausing %ds so the cache (ttl=%ds) expires ...%n", waitSeconds, ttlSeconds);
        Thread.sleep(waitSeconds * 1000L);
      }
    }

    System.out.println(
        "\n"
            + "Done. Once created, the cache is reused on later turns until it expires; the pause"
            + " then");
    System.out.println(
        "triggers a RE-CREATION under a new name, usually with a larger cachedContents count as"
            + " the");
    System.out.println(
        "settled history is folded in. Exact turn-by-turn timing depends on model" + " latency.");
  }

  /** Cache details observed during a single turn. */
  private static final class TurnSummary {
    CacheMetadata cache;
    Integer cachedTokens;
  }

  private static String textOf(List<Part> parts) {
    if (parts == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (Part part : parts) {
      if (part.getText() != null) {
        if (builder.length() > 0) {
          builder.append(' ');
        }
        builder.append(part.getText());
      }
    }
    return builder.toString();
  }

  private static boolean hasApiKey() {
    return !isNullOrBlank(System.getenv("GEMINI_API_KEY"))
        || !isNullOrBlank(System.getenv("GOOGLE_API_KEY"));
  }

  private static boolean isNullOrBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Classifies a turn's cache metadata relative to the previously seen active cache name. */
  private static String cacheStatus(CacheMetadata cache, String previousActiveName) {
    if (!cache.isActive()) {
      return "no cache yet (fingerprint only)";
    }
    if (previousActiveName == null) {
      return "CREATED " + cache.getCacheName();
    }
    if (!cache.getCacheName().equals(previousActiveName)) {
      return "RE-CREATED "
          + cache.getCacheName()
          + " (previous "
          + previousActiveName
          + " expired)";
    }
    return "REUSED " + cache.getCacheName();
  }

  /**
   * Builds the large shared weather reference used as the cacheable prefix (the system
   * instruction). Explicit context caching only starts once that prefix exceeds Gemini's minimum
   * cache size, so this reference is intentionally large.
   */
  private static String buildWeatherReference() {
    StringBuilder out = new StringBuilder();
    out.append(
            "You are a weather assistant. Answer questions using ONLY the weather reference below,"
                + " keep answers to one or two sentences, and cite the section or city you used.")
        .append('\n')
        .append('\n');

    List<String[]> guidelines =
        List.of(
            new String[] {
              "Forecasting basics",
              "A forecast combines current observations, numerical model guidance, and local"
                  + " climatology; always state the valid time window and your confidence."
            },
            new String[] {
              "Wind chill",
              "Wind chill is how cold the air feels once wind is accounted for. It is only defined"
                  + " for temperatures at or below 10C and wind above 5 km/h."
            },
            new String[] {
              "Heat index",
              "The heat index, or apparent temperature, combines air temperature and humidity. It"
                  + " is meaningful above about 27C when relative humidity is high."
            },
            new String[] {
              "Precipitation types",
              "Rain, drizzle, sleet, freezing rain, snow, and hail are distinguished by the"
                  + " temperature profile between the cloud base and the ground."
            },
            new String[] {
              "Storm safety",
              "During a thunderstorm, move indoors, avoid open fields and tall isolated trees, and"
                  + " stay off corded electronics until 30 minutes after the last thunder."
            },
            new String[] {
              "Units",
              "Temperatures are in degrees Celsius, wind speed in km/h, and precipitation in"
                  + " millimeters unless a value states otherwise."
            });
    for (int i = 0; i < guidelines.size(); i++) {
      out.append("Section ")
          .append(i + 1)
          .append(" (")
          .append(guidelines.get(i)[0])
          .append("): ")
          .append(guidelines.get(i)[1])
          .append('\n');
    }
    out.append('\n');

    out.append("Climate normals for named cities:").append('\n');
    List<String> cities =
        List.of(
            "Marisol: coastal and mild; January average 12C, July average 24C, annual rainfall 640"
                + " mm.",
            "Fjordheim: cold maritime; January average -6C, July average 15C, annual rainfall 900"
                + " mm.",
            "Solara: hot desert; January average 18C, July average 41C, annual rainfall 90 mm.",
            "Verdant: temperate rainforest; January average 7C, July average 19C, annual rainfall"
                + " 2400 mm.",
            "Highpoint: alpine; January average -9C, July average 12C, annual rainfall 1100 mm.",
            "Puerto Brisa: tropical; January average 26C, July average 29C, annual rainfall 1800"
                + " mm.",
            "Windgate: windy plains; January average -2C, July average 27C, annual rainfall 520"
                + " mm.",
            "Frosthollow: subarctic; January average -22C, July average 16C, annual rainfall 380"
                + " mm.");
    for (String city : cities) {
      out.append("  ").append(city).append('\n');
    }
    out.append('\n');

    // Generated station rows that pad the prefix past the model's minimum cacheable size.
    out.append("Automated station normals:").append('\n');
    String[] windDirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    for (int index = 1; index <= 100; index++) {
      int janAvg = -12 + (index * 7) % 34;
      int julAvg = 14 + (index * 3) % 22;
      int rainfall = 250 + (index * 37) % 2300;
      String windDir = windDirs[index % 8];
      int windSpeed = 6 + (index * 5) % 38;
      out.append("  Station S")
          .append(index)
          .append(" (sector ")
          .append((char) ('A' + index % 8))
          .append(index % 12)
          .append("): January average ")
          .append(janAvg)
          .append("C, July average ")
          .append(julAvg)
          .append("C, annual rainfall ")
          .append(rainfall)
          .append(" mm, prevailing wind ")
          .append(windDir)
          .append(" at ")
          .append(windSpeed)
          .append(" km/h.")
          .append('\n');
    }
    return out.toString();
  }

  private ContextCachingDemoAgentJava() {}
}
