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
  id("com.android.library")
  kotlin("plugin.serialization")
  id("maven-publish")
  alias(libs.plugins.ksp)
  alias(libs.plugins.gradle.test.retry)
}

// AGP's built-in Kotlin doesn't apply the kotlin.jvm/multiplatform plugins that
// the root project pins coreLibrariesVersion on, so set it here too. Keeps the
// published AAR on the 2.1 stdlib and consumable by Kotlin 2.1 projects.
kotlin { coreLibrariesVersion = rootProject.extra["kotlinCoreLibrariesVersion"] as String }

dependencies {
  implementation(platform(libs.google.firebase.platform))
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.google.firebase.ai)
  implementation(libs.kotlinx.serialization)

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.test.runner)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.google.truth)
  testImplementation(libs.robolectric)

  // Generates `@Tool` FunctionTools for the unit tests (e.g. WeatherTools.generatedTools()).
  add("kspTest", project(":google-adk-kotlin-processor"))
}

android {
  namespace = "com.google.adk.firebase"

  testOptions {
    unitTests {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
      all {
        it.systemProperty("robolectric.logging", "stderr")
        it.retry {
          maxRetries = 2
          filter { includeClasses.add("*IntegrationTest") }
        }
      }
    }
  }

  // Publishes the Android `release` variant as a single AAR with sources and
  // javadoc jars. AGP's `withJavadocJar()` runs Gradle's `javadoc` task,
  // which doesn't understand `.kt` sources, so the resulting jar is
  // effectively empty - but it still satisfies Maven Central's per-module
  // requirement. Replacing with Dokka HTML would require building the jar
  // manually and attaching it to the AGP-created publication after
  // evaluation; left as a follow-up.
  publishing {
    singleVariant("release") {
      withSourcesJar()
      withJavadocJar()
    }
  }

  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 26

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
}

// AGP registers a software component named `release` (created by the
// `android { publishing { singleVariant("release") { ... } } }` block above),
// but it does NOT auto-create a `MavenPublication` for it — that's our
// responsibility. We have to do this inside `afterEvaluate` because the
// component itself is only registered once the Android extension finishes
// configuring. POM metadata and GPG signing are configured in the root
// build.gradle.kts; the root script also intentionally skips attaching the
// Dokka javadoc jar to this publication because AGP's `withJavadocJar()`
// above already attaches one.
afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "google-adk-kotlin-firebase-android"
      }
    }
  }
}
