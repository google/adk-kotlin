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
    val commonMain =
      getByName("commonMain") {
        dependencies {
          implementation(project(":google-adk-kotlin-core"))
          implementation(libs.kotlinx.coroutines.core)
        }
      }
    getByName("commonTest") {
      dependencies {
        implementation(project(":google-adk-kotlin-testing"))
        implementation(kotlin("test"))
      }
    }
    val commonJvmAndroidMain =
      create("commonJvmAndroidMain") {
        dependsOn(commonMain)
        dependencies {
          implementation(libs.kotlinx.serialization)
          implementation(libs.a2a.sdk.client)
          implementation(libs.a2a.sdk.common)
          implementation(libs.a2a.sdk.spec)
          // The A2A SDK's POM does not declare the JSpecify annotations it uses, so Kotlin
          // cannot read its annotated types (KT-80247; an error from language version 2.4).
          // Compile-only.
          compileOnly(libs.jspecify)
        }
      }
    // jvmMain hosts the deprecated v0.3 path (JVM-only).
    getByName("jvmMain") {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        // JVM v1.0 factory uses the SDK's proto-based JSON-RPC transport; kept off the Android
        // path.
        implementation(libs.a2a.sdk.transport.jsonrpc)
        // Jackson is JVM-only (deprecated v0.3 converters); kept off the Android artifact.
        implementation(libs.jackson.databind)
        implementation(libs.jackson.datatype.jsr310)
        implementation(libs.jackson.module.kotlin)
        implementation(libs.a2a.legacy.sdk.client)
        implementation(libs.a2a.legacy.sdk.common)
        implementation(libs.a2a.legacy.sdk.spec)
      }
    }
    getByName("jvmTest") {
      dependencies {
        implementation(libs.junit)
        implementation(libs.google.truth)
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.okhttp.mockwebserver)
        implementation(libs.a2a.legacy.sdk.client)
        implementation(libs.a2a.legacy.sdk.spec)
      }
    }
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
