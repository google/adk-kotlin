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
  id("com.android.library")
  alias(libs.plugins.vanniktech.maven.publish)
  kotlin("plugin.serialization")
  id("org.jetbrains.kotlin.android")
}

dependencies {
  implementation(platform(libs.google.firebase.platform))
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.google.firebase.ai)
  implementation(libs.kotlinx.serialization)

  testImplementation(kotlin("test"))
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.test.runner)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.google.truth)
  testImplementation(libs.robolectric)
}

android {
  namespace = "com.google.adk.firebase"

  testOptions {
    unitTests {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
      all { it.systemProperty("robolectric.logging", "stderr") }
    }
  }

  // The `singleVariant("release") { withSourcesJar() }` configuration that
  // used to live here is now managed by the vanniktech plugin via the
  // `AndroidSingleVariantLibrary` configurator below.

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

mavenPublishing {
  // Publishes the Android `release` variant as a single AAR with sources and
  // javadoc jars. The other modules wire their `-javadoc.jar` to Dokka's HTML
  // output, but `AndroidSingleVariantLibrary` does not expose a Dokka hook;
  // it always uses AGP's `withJavadocJar()`, which runs Gradle's `javadoc`
  // task. For pure-Kotlin Android sources that produces an effectively empty
  // jar, but it still satisfies Maven Central's per-module requirement.
  // Replacing it with a Dokka-fed jar would require building the artifact
  // manually and attaching it to the auto-created publication after
  // evaluation; that is left as a follow-up. Shared POM metadata and signing
  // are configured in the root `build.gradle.kts`.
  configure(
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      variant = "release",
      sourcesJar = true,
      publishJavadocJar = true,
    )
  )
  coordinates(artifactId = "google-adk-kotlin-firebase-android")
}
