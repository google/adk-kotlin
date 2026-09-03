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

// Java-only examples sub-project: no Kotlin plugin, so these ports are compiled by javac against
// the
// ADK modules' published JVM artifacts. That verifies the ADK is usable purely through its
// Java-facing surface, rather than as Java sources compiled inside the Kotlin/KMP examples project.
plugins { id("java") }

// The LiteRT-LM example and its SDK are compiled for Java 21, so this project needs a 21+
// toolchain.
val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()

java { toolchain { languageVersion = JavaLanguageVersion.of(maxOf(21, jdkVersion)) } }

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(project(":google-adk-kotlin-a2a"))
  implementation(libs.a2a.sdk.client)
  implementation(libs.a2a.sdk.spec)
  implementation(libs.a2a.sdk.transport.jsonrpc)
  // The interop helpers hand back a kotlinx.coroutines Flow and a Reactive Streams Publisher, which
  // core exposes only as implementation dependencies, so Java callers need them on their own
  // compile classpath.
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.reactive)
  implementation(project(":google-adk-kotlin-litertlm"))
  implementation(libs.google.ai.edge.litertlm.jvm)
  implementation(libs.opentelemetry.sdk)
  implementation(project(":google-adk-kotlin-integrations"))
}

// Runs a Java example agent in a REPL, e.g.:
//   ./gradlew :google-adk-kotlin-examples-java:runJavaExample \
//       --args="com.google.adk.kt.examples.hello.HelloAgentJava"
tasks.register<JavaExec>("runJavaExample") {
  group = "application"
  description = "Runs a Java example agent (pass its class name via --args)."
  mainClass.set("com.google.adk.kt.examples.JavaExampleRunner")
  classpath = sourceSets["main"].runtimeClasspath
  standardInput = System.`in`
}
