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
    getByName("commonMain") {
      dependencies {
        api(project(":google-adk-kotlin-core"))
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    getByName("commonTest") { dependencies { implementation(kotlin("test")) } }
    getByName("jvmMain") {
      dependencies {
        // Spring AI 2.0 model abstractions (ChatModel, messages, tools, content). Exposed on the
        // API surface because the constructor takes a Spring AI ChatModel. Requires Spring Boot 4 /
        // Java 17 at the consumer.
        api(libs.spring.ai.model)
        // Bridges Spring AI's Reactor Flux into a Kotlin Flow.
        implementation(libs.kotlinx.coroutines.reactive)
      }
    }
    getByName("jvmTest") {
      dependencies {
        implementation(libs.junit)
        implementation(libs.google.truth)
        implementation(libs.mockito.kotlin)
        // Real Spring AI provider for the (opt-in) live check against Vertex Gemini.
        implementation(libs.spring.ai.google.genai)
      }
    }
  }
}

// Coordinates the Kotlin Multiplatform plugin uses for the publications it
// auto-creates:
//   - `kotlinMultiplatform` -> google-adk-kotlin-integrations-spring     (root metadata)
//   - `jvm`                 -> google-adk-kotlin-integrations-spring-jvm (KMP target)
// POM metadata, Dokka javadoc, and GPG signing are configured in the root
// build.gradle.kts.
publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform") {
      artifactId = "google-adk-kotlin-integrations-spring"
    }
  }
}
