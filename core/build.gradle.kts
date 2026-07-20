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
  kotlin("plugin.serialization")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.ksp)
  alias(libs.plugins.gradle.test.retry)
  id("maven-publish")
}

kotlin {
  // AGP 9 KMP Android library target (replaces com.android.library + androidTarget).
  android {
    namespace = "com.google.adk"
    compileSdk = rootProject.extra["androidCompileSdk"] as Int
    minSdk = rootProject.extra["androidMinSdk"] as Int

    // Host-side (Robolectric) unit tests; opt-in with the new plugin.
    withHostTest {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }

    // Enable Android resource/asset processing for the target. The KMP Android library plugin gates
    // the assets pipeline behind this flag; without it the device-test APK packages no assets and
    // GenaiPromptInstrumentedTest can't load the `test-image.png` fixture. The androidComponents
    // block below shares that fixture with the device-test component.
    androidResources { enable = true }

    // Device-side (instrumented) tests; opt-in with the new plugin.
    withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }

    packaging {
      resources {
        merges += "**/META-INF/INDEX.LIST"
        merges += "**/META-INF/DEPENDENCIES"
      }
    }
  }
  jvm()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(libs.kotlinx.atomicfu)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.datetime)
        implementation(libs.kotlinx.serialization)
        api(libs.google.genai.kotlin)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(project(":google-adk-kotlin-testing"))
        implementation(kotlin("test"))
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
        implementation(libs.org.json)
      }
    }
    val commonJvmAndroidMain by creating {
      dependsOn(commonMain)
      dependencies {
        implementation(libs.kxml2)
        implementation(libs.snakeyaml)
        // Shared OpenTelemetry-backed tracer implementation (telemetry/otel) compiles into both the
        // JVM and Android targets. opentelemetry-context is pulled in transitively by the API.
        implementation(libs.opentelemetry.api)
      }
    }
    val commonJvmAndroidTest by creating { dependsOn(commonTest) }
    val jvmMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        implementation(libs.google.gson)
        implementation(libs.google.cloud.storage)
        implementation(libs.mcp)
        implementation(libs.kotlinx.coroutines.reactor)
        implementation(libs.slf4j.api)
        implementation(libs.google.flogger.extensions)
      }
    }
    val jvmTest by getting {
      dependsOn(commonJvmAndroidTest)
      // Leaf (jvm/android) test source dir for KSP-generated `@Tool` `FunctionTool`s; KMP forbids
      // `common*` test sets from referencing per-platform KSP output.
      kotlin.srcDir("src/jvmAndroidKspTest/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
        // GeminiJvmTest asserts tracking headers on real requests via a local MockWebServer.
        implementation(libs.okhttp.mockwebserver)
        // OtelTracerTest exercises a real span-export round-trip through the OpenTelemetry SDK.
        implementation(libs.opentelemetry.sdk)
        // McpToolsetIntegrationTest uses org.junit.Assume to gate on ADK_MCP_DISABLE_IT.
        implementation(libs.junit)
      }
    }
    val androidMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        implementation(
          libs.google.auth.oauth2.http
        ) // Android compatible version or use separate for Android if needed.
        implementation(libs.google.mlkit.genai.prompt)
        implementation(libs.androidx.room.runtime)
        implementation(libs.androidx.room.ktx)
      }
    }
    val androidHostTest by getting {
      dependsOn(commonJvmAndroidTest)
      // See the `jvmTest` note: dedicated platform-test source dir for KSP-generated tools.
      kotlin.srcDir("src/jvmAndroidKspTest/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.androidx.test.core)
        implementation(libs.androidx.test.ext.junit)
        implementation(libs.androidx.test.runner)
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
        implementation(libs.robolectric)
      }
    }

    val androidDeviceTest by getting {
      dependencies {
        implementation(libs.androidx.compose.ui.test.junit4)
        implementation(libs.androidx.compose.ui.test.manifest)
        implementation(libs.androidx.test.espresso.core)
        implementation(libs.androidx.test.ext.junit)
        implementation(libs.androidx.test.rules)
        implementation(libs.androidx.test.runner)
        implementation(libs.google.truth)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.mockito.android)
      }
    }
  }
}

// Share the `test-image.png` fixture with the on-device instrumented test APK. The image is stored
// once, in the host-test assets (src/androidHostTest/assets), and consumed by both the host test
// (GenaiPromptConversionsTest) and the device test (GenaiPromptInstrumentedTest).
extensions.configure<com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension>(
  "androidComponents"
) {
  onVariants { variant ->
    variant.deviceTests.forEach { (_, deviceTest) ->
      deviceTest.sources.assets?.addStaticSourceDirectory("src/androidHostTest/assets")
    }
  }
}

// Retry only integration tests (classes named *IntegrationTest) to absorb flakiness from the real
// subprocess + stdio transport in McpToolsetIntegrationTest, without masking unit-test failures.
tasks.withType<Test>().configureEach {
  retry {
    maxRetries = 2
    filter { includeClasses.add("*IntegrationTest") }
  }
}

// Coordinates the Kotlin Multiplatform plugin uses for the publications it
// auto-creates:
//   - `kotlinMultiplatform` -> google-adk-kotlin-core         (root metadata)
//   - `jvm`                 -> google-adk-kotlin-core-jvm     (KMP target)
//   - `androidRelease`      -> google-adk-kotlin-core-android (KMP target)
// We only need to set the root publication's artifactId explicitly; per-target
// suffixes (`-jvm`, `-android`) are appended by the KMP plugin automatically.
// POM metadata, Dokka javadoc, and GPG signing are configured in the root
// build.gradle.kts.
publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform") {
      artifactId = "google-adk-kotlin-core"
    }
  }
}

dependencies {
  // Run the KSP `FunctionTool` processor on the JVM and Android host-test compilations so the
  // `jvmAndroidKspTest` `@Tool` fixtures generate their `FunctionTool` subclasses. The
  // single-variant
  // KMP Android library plugin exposes just `kspAndroidHostTest`.
  add("kspJvmTest", project(":google-adk-kotlin-processor"))
  add("kspAndroidHostTest", project(":google-adk-kotlin-processor"))
}

// Room's annotation processor runs via KSP. Wire it only against the Android target since the
// Room runtime is androidMain-only.
dependencies { add("kspAndroid", libs.androidx.room.compiler) }

// Export the Room schema so migrations can be validated/generated. Baselines are committed under
// core/schemas (the same directory the Blaze build writes to via -Aroom.schemaLocation).
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Gradle 9 strictly validates task dependencies, and AGP's Android lint tasks
// read the KSP-generated sources; declare the dependency explicitly.
val kspTasks = tasks.matching { it.name.startsWith("ksp") }

tasks
  .matching { it.name.startsWith("lintAnalyze") || it.name.endsWith("LintModel") }
  .configureEach { dependsOn(kspTasks) }
