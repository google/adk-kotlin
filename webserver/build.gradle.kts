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
  kotlin("jvm")
  id("java-library")
  id("maven-publish")
}

// Attach a sources jar to the `java` component. The `-javadoc.jar` is
// attached by the root build.gradle.kts and is fed from Dokka HTML (Gradle's
// `javadoc` task can't process .kt sources). POM metadata and GPG signing
// are configured in the root build.gradle.kts.
java { withSourcesJar() }

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "google-adk-kotlin-webserver"
    }
  }
}

sourceSets {
  main { java.srcDirs("src/jvmMain/kotlin") }
  test { java.srcDirs("src/jvmTest/kotlin") }
}

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.kotlinx.datetime)
  implementation(libs.kotlinx.coroutines.core)

  implementation(libs.graphviz.java)

  implementation(libs.opentelemetry.api)
  implementation(libs.opentelemetry.sdk)

  implementation(libs.ktor.serialization.gson)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.slf4j.api)
  implementation(libs.slf4j.simple)

  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.google.truth)
  testImplementation(libs.junit)
}

tasks.named<ProcessResources>("processResources") { from("browser") { into("browser") } }
