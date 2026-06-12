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
  alias(libs.plugins.ksp)
}

// The examples depend on the litertlm module and the litert-lm SDK, which are
// compiled for Java 21 (class-file version 65). This module must therefore use
// a JDK 21+ toolchain even though the rest of the project defaults to JDK 17.
// Honor a higher `jdkVersion` if one is explicitly requested.
val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()

kotlin { jvmToolchain(maxOf(21, jdkVersion)) }

sourceSets { main { java.srcDirs("src/main/kotlin") } }

dependencies {
  implementation(project(":google-adk-kotlin-a2a"))
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.a2a.sdk.client)
  implementation(libs.a2a.sdk.spec)
  implementation(libs.a2a.sdk.transport.jsonrpc)
  implementation(project(":google-adk-kotlin-litertlm"))
  implementation(libs.google.ai.edge.litertlm.jvm)
  implementation(libs.google.cloud.storage)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.opentelemetry.sdk)

  ksp(project(":google-adk-kotlin-processor"))
}
