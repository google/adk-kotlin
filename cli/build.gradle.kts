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
  application
  id("maven-publish")
  id("signing")
}

description = "ADK Kotlin - CLI"

application { mainClass.set("com.google.adk.kt.cli.AdkCliKt") }

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(project(":google-adk-kotlin-webserver"))
  implementation("com.github.ajalt.clikt:clikt:5.0.3")
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "google-adk-kotlin-cli"
    }
  }
}
