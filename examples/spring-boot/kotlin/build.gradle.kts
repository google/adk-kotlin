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

// An order-support service written in Kotlin, with KSP-generated tools and coroutine handlers.
// Run it with ./gradlew :google-adk-kotlin-examples-spring-boot-kotlin:bootRun; see README.md.
plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  kotlin("plugin.serialization")
  alias(libs.plugins.ksp)
  alias(libs.plugins.spring.boot)
}

dependencies {
  // Unlike the dependency-management plugin, platform() cannot downgrade the ADK's dependencies.
  implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.spring.boot.starter.webflux)
  // Spring's Kotlin support: JSON for data classes, suspend handlers and Flow responses.
  implementation(libs.jackson.module.kotlin)
  implementation(libs.kotlin.reflect)
  implementation(libs.kotlinx.coroutines.reactor)

  // kotlinx.serialization for the @Serializable data classes the workflow's function nodes pass.
  implementation(libs.kotlinx.serialization)

  ksp(project(":google-adk-kotlin-processor"))

  testImplementation(libs.spring.boot.starter.webflux.test)
  testImplementation(libs.google.truth)
  testImplementation(libs.micrometer.context.propagation)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
