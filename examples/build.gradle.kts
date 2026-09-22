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

sourceSets { main { java.srcDirs("src/main/kotlin", "src/main/java") } }

dependencies {
  implementation(project(":google-adk-kotlin-a2a"))
  implementation(project(":google-adk-kotlin-core"))
  implementation(project(":google-adk-kotlin-integrations"))
  implementation(project(":google-adk-kotlin-integrations-spring"))
  implementation(libs.spring.ai.google.genai)
  implementation(libs.a2a.sdk.client)
  implementation(libs.a2a.sdk.spec)
  implementation(libs.a2a.sdk.transport.jsonrpc)
  implementation(project(":google-adk-kotlin-litertlm"))
  implementation(project(":google-adk-kotlin-webserver"))
  implementation(libs.clikt)
  implementation(libs.github.api)
  implementation(libs.google.ai.edge.litertlm.jvm)
  implementation(libs.google.cloud.storage)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.opentelemetry.sdk)

  ksp(project(":google-adk-kotlin-processor"))
}

// Convenience task to run the ADK Docs Release Analyzer sample, e.g.:
//   ./gradlew :google-adk-kotlin-examples:runDocsReleaseAnalyzer \
//       --args="--start-tag v0.1.0 --end-tag v0.2.0"
tasks.register<JavaExec>("runDocsReleaseAnalyzer") {
  group = "application"
  description = "Runs the ADK Docs Release Analyzer agent."
  mainClass.set("com.google.adk.kt.examples.github.adkreleasedocs.AdkDocsReleaseAnalyzerAgentKt")
  classpath = sourceSets["main"].runtimeClasspath
}

// Convenience task to run the BigQuery Analytics Demo sample, e.g.:
//   export BIGQUERY_PROJECT_ID=my-gcp-project
//   export BIGQUERY_DATASET_ID=my_dataset
//   ./gradlew :google-adk-kotlin-examples:runBigQueryAnalyticsDemo
tasks.register<JavaExec>("runBigQueryAnalyticsDemo") {
  group = "application"
  description = "Runs the BigQuery Analytics Demo agent."
  mainClass.set("com.google.adk.kt.examples.plugins.BigQueryAnalyticsDemoAgentKt")
  classpath = sourceSets["main"].runtimeClasspath
}

// Convenience task to run the Spring AI tools demo against Vertex Gemini, e.g.:
//   GOOGLE_CLOUD_PROJECT=my-project GOOGLE_API_USE_CLIENT_CERTIFICATE=false \
//       ./gradlew :google-adk-kotlin-examples:runSpringAiToolsDemo
tasks.register<JavaExec>("runSpringAiToolsDemo") {
  group = "application"
  description = "Runs the Spring AI adapter and tool-bridge demo cases."
  mainClass.set("com.google.adk.kt.examples.springai.SpringAiToolsDemoKt")
  classpath = sourceSets["main"].runtimeClasspath
  System.getenv("GOOGLE_CLOUD_PROJECT")?.let { environment("GOOGLE_CLOUD_PROJECT", it) }
  System.getenv("GOOGLE_CLOUD_LOCATION")?.let { environment("GOOGLE_CLOUD_LOCATION", it) }
  System.getenv("GOOGLE_API_USE_CLIENT_CERTIFICATE")?.let {
    environment("GOOGLE_API_USE_CLIENT_CERTIFICATE", it)
  }
}

// Convenience task to serve a sample of the example agents over HTTP, e.g.:
//   GOOGLE_API_KEY=... ./gradlew :google-adk-kotlin-examples:runServer --args="--dev"
// then browse http://localhost:8080/dev-ui and pick an agent.
tasks.register<JavaExec>("runServer") {
  group = "application"
  description =
    "Serves five example agents over HTTP, unauthenticated, with the Development UI under --dev."
  mainClass.set("com.google.adk.kt.examples.server.ExampleServerKt")
  classpath = sourceSets["main"].runtimeClasspath
}
