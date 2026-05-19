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
  id("com.vanniktech.maven.publish")
  kotlin("plugin.serialization")
  id("org.jetbrains.kotlin.android")
}

mavenPublishing {
  coordinates(project.group.toString(), "google-adk-kotlin-firebase-android", project.version.toString())
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
    }
  }

  compileSdk = 35

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

