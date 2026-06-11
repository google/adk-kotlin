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
  id("java-library")
  id("maven-publish")
}

sourceSets {
  main {
    kotlin.setSrcDirs(listOf("src/jvmMain/kotlin"))
    resources.setSrcDirs(listOf("src/jvmMain/resources"))
  }
  test {
    kotlin.setSrcDirs(listOf("src/jvmTest/kotlin"))
    resources.setSrcDirs(listOf("src/jvmTest/resources"))
  }
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
      artifactId = "google-adk-kotlin-processor"
    }
  }
}

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.kotlinx.coroutines.core)
  implementation("com.squareup:kotlinpoet:1.16.0")
  implementation("com.squareup:kotlinpoet-ksp:1.16.0")
  implementation("com.google.devtools.ksp:symbol-processing-api:1.9.23-1.0.19")

  kspTest(project(":google-adk-kotlin-processor"))

  testImplementation(libs.google.truth)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
