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
  id("maven-publish")
}

kotlin {
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
        implementation(libs.jackson.databind)
        implementation(libs.jackson.datatype.jsr310)
        implementation(libs.jackson.module.kotlin)
        implementation(libs.a2a.sdk.client)
        implementation(libs.a2a.sdk.common)
        implementation(libs.a2a.sdk.spec)
        implementation(libs.a2a.sdk.transport.rest)
      }
    }
    val jvmMain by getting { dependsOn(commonJvmAndroidMain) }
  }
}

// Coordinates the Kotlin Multiplatform plugin uses for the publications it
// auto-creates:
//   - `kotlinMultiplatform` -> google-adk-kotlin-a2a     (root metadata)
//   - `jvm`                 -> google-adk-kotlin-a2a-jvm (KMP target)
// POM metadata, Dokka javadoc, and GPG signing are configured in the root
// build.gradle.kts.
publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform") {
      artifactId = "google-adk-kotlin-a2a"
    }
  }
}
