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

plugins {
  // Kotlin is compiled by AGP's built-in Kotlin support (no kotlin-android plugin).
  id("com.android.application")
}

// This sample transitively depends on androidx.core:core:1.16.0 (via the ML Kit GenAI / Gemini Nano
// stack), which requires compileSdk >= 35. Pin this example to at least 35 so it builds on its own
// without raising the repo-wide default (kept lower for the published libraries). core needs 35 the
// same way for its instrumentation tests (passed there via -PandroidCompileSdk=35).
val exampleCompileSdk = maxOf(35, rootProject.extra["androidCompileSdk"] as Int)

android {
  namespace = "com.google.adk.kt.examples.android"
  compileSdk = exampleCompileSdk

  defaultConfig {
    applicationId = "com.google.adk.kt.examples.android"
    minSdk = rootProject.extra["androidMinSdk"] as Int
    targetSdk = exampleCompileSdk
    versionCode = 1
    versionName = "0.1.0"

    // GEMINI_API_KEY is consumed by the SkillsAssetSourceActivity's manifest meta-data placeholder
    // (see SkillsAssetSourceAndroidManifest.xml). Sourced from a Gradle property or environment
    // variable; defaults to the literal placeholder so APKs built without a key fail-fast with a
    // user-visible message rather than calling Gemini with a blank key.
    manifestPlaceholders["GEMINI_API_KEY"] =
      providers
        .gradleProperty("GEMINI_API_KEY")
        .orElse(providers.environmentVariable("GEMINI_API_KEY"))
        .getOrElse("\${GEMINI_API_KEY}")
  }

  // The genai/auth transitive dependencies each ship a META-INF/INDEX.LIST and
  // META-INF/DEPENDENCIES, which collide when packaging the APK. Merge them, as
  // the root build does for the library modules.
  packaging {
    resources {
      merges += "**/META-INF/INDEX.LIST"
      merges += "**/META-INF/DEPENDENCIES"
    }
  }
}

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.google.mlkit.genai.prompt)
  implementation(libs.kotlinx.coroutines.core)
}
