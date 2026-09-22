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

// JVM-only: Spring AI is a server-side library with no Android variant, so this module targets the
// JVM directly rather than Kotlin Multiplatform.
plugins {
  kotlin("jvm")
  id("java-library")
  id("maven-publish")
}

// Attach a sources jar to the `java` component. The `-javadoc.jar` is attached by the root
// build.gradle.kts and fed from Dokka HTML; POM metadata and GPG signing are configured there too.
java { withSourcesJar() }

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "google-adk-kotlin-integrations-spring"
    }
  }
}

sourceSets {
  main {
    java.srcDirs("src/jvmMain/kotlin")
    resources.srcDirs("src/jvmMain/resources")
  }
  test { java.srcDirs("src/jvmTest/kotlin") }
}

dependencies {
  api(project(":google-adk-kotlin-core"))
  implementation(libs.kotlinx.coroutines.core)
  // Spring AI 2.0 model abstractions (ChatModel, messages, tools, content). Exposed on the API
  // surface because the constructor takes a Spring AI ChatModel. Requires Spring Boot 4 / Java 17
  // at the consumer.
  api(libs.spring.ai.model)
  // Bridges Spring AI's Reactor Flux into a Kotlin Flow.
  implementation(libs.kotlinx.coroutines.reactive)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
  testImplementation(libs.google.truth)
  testImplementation(libs.mockito.kotlin)
  // Real Spring AI Google GenAI provider: SpringAiToolsTest uses its GoogleGenAiChatOptions to
  // exercise buildChatOptions against a concrete provider option type, and it backs the opt-in live
  // check against Vertex Gemini.
  testImplementation(libs.spring.ai.google.genai)
}
