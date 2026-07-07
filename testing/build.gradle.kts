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

// Source-only KMP test fixtures shared by core and a2a tests. Not published; consumed only by other
// modules' test source sets (see b/532049194).

plugins {
  kotlin("multiplatform")
  id("com.android.kotlin.multiplatform.library")
}

kotlin {
  android {
    namespace = "com.google.adk.testing"
    compileSdk = rootProject.extra["androidCompileSdk"] as Int
    minSdk = rootProject.extra["androidMinSdk"] as Int
  }
  jvm()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":google-adk-kotlin-core"))
        implementation(libs.kotlinx.coroutines.core)
      }
    }
  }
}
