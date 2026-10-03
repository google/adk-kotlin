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

// JVM-only: the Anthropic Java SDK has no Android or multiplatform variant, so this module targets
// the JVM directly rather than Kotlin Multiplatform.
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
      artifactId = "google-adk-kotlin-integrations-anthropic"
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
  // The official Anthropic SDK. Exposed on the API surface because a constructor takes an
  // `AnthropicClient`, so callers can configure any backend the SDK supports.
  api(libs.anthropic.java)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
  testImplementation(libs.google.truth)
  // Drives the real SDK client against canned HTTP responses.
  testImplementation(libs.okhttp.mockwebserver)
}
