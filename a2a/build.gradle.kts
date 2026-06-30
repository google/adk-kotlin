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
  id("maven-publish")
}

kotlin {
  androidTarget { publishLibraryVariants("release") }
  jvm()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":google-adk-kotlin-core"))
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    val commonJvmAndroidMain by creating {
      dependsOn(commonMain)
      dependencies {
        implementation(libs.kotlinx.serialization)
        implementation(libs.jackson.databind)
        implementation(libs.jackson.datatype.jsr310)
        implementation(libs.a2a.sdk.client)
        implementation(libs.a2a.sdk.common)
        implementation(libs.a2a.sdk.spec)
        implementation(libs.a2a.sdk.transport.rest)
      }
    }
    // jvmMain hosts the deprecated v0.3 path (JVM-only); androidMain stays v1.0-only.
    val jvmMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        // Only the deprecated v0.3 (JVM-only) converters use jackson-module-kotlin; keep it off the
        // Android path, which serializes via kotlinx.serialization instead.
        implementation(libs.jackson.module.kotlin)
        implementation(libs.a2a.legacy.sdk.client)
        implementation(libs.a2a.legacy.sdk.common)
        implementation(libs.a2a.legacy.sdk.spec)
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation(libs.junit)
        implementation(libs.google.truth)
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.a2a.legacy.sdk.client)
        implementation(libs.a2a.legacy.sdk.spec)
      }
    }
    val androidMain by getting { dependsOn(commonJvmAndroidMain) }
  }
}

android {
  namespace = "com.google.adk.a2a"

  // Required so the KMP `androidRelease` publication picks up sources. The
  // Dokka-backed javadoc jar is attached by the root build.gradle.kts.
  publishing { singleVariant("release") { withSourcesJar() } }
}

// Coordinates the Kotlin Multiplatform plugin uses for the publications it
// auto-creates:
//   - `kotlinMultiplatform` -> google-adk-kotlin-a2a         (root metadata)
//   - `jvm`                 -> google-adk-kotlin-a2a-jvm     (KMP target)
//   - `androidRelease`      -> google-adk-kotlin-a2a-android (KMP target)
// Per-target suffixes (`-jvm`, `-android`) are appended by the KMP plugin
// automatically. POM metadata, Dokka javadoc, and GPG signing are configured in
// the root build.gradle.kts.
publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform") {
      artifactId = "google-adk-kotlin-a2a"
    }
  }
}
