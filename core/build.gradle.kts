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
  kotlin("multiplatform")
  id("com.android.library")
  id("com.vanniktech.maven.publish")
}

kotlin {
  androidTarget { publishLibraryVariants("release") }
  jvm()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.datetime)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
        implementation(libs.org.json)
      }
    }
    val commonJvmAndroidMain by creating {
      dependsOn(commonMain)
      dependencies { implementation(libs.kxml2) }
    }
    val commonJvmAndroidTest by creating { dependsOn(commonTest) }
    val jvmMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        implementation(libs.google.gson)
        implementation(libs.google.cloud.storage)
        implementation(libs.google.genai)
        implementation(libs.mcp)
        implementation(libs.opentelemetry.api)
        implementation(libs.kotlinx.coroutines.reactor)
        implementation(libs.slf4j.api)
        implementation(libs.snakeyaml)
        implementation(libs.google.flogger.extensions)
      }
    }
    val jvmTest by getting {
      dependsOn(commonJvmAndroidTest)
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
      }
    }
    val androidMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        // Use standard Android genai or similar if available for OSS
        implementation(
          libs.google.genai
        ) // Same as JVM if it's multiplatform, or use specific android one if separate.
        implementation(
          libs.google.auth.oauth2.http
        ) // Android compatible version or use separate for Android if needed.
        implementation(libs.google.mlkit.genai.prompt)
      }
    }
    val androidUnitTest by getting {
      dependsOn(commonJvmAndroidTest)
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.androidx.test.core)
        implementation(libs.androidx.test.ext.junit)
        implementation(libs.androidx.test.runner)
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
        implementation(libs.robolectric)
      }
    }
  }
}

android {
  namespace = "com.google.adk"

  testOptions {
    unitTests {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
    }
  }
}

