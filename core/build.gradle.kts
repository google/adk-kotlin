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
  id("com.android.library")
  id("maven-publish")
}

kotlin {
  androidTarget { publishLibraryVariants("release") }
  jvm()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.datetime)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
        implementation(libs.org.json)
      }
    }
    val commonJvmAndroidMain by creating {
      dependsOn(commonMain)
      dependencies { implementation(libs.kxml2) }
    }
    val commonJvmAndroidTest by creating { dependsOn(commonTest) }
    val jvmMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        implementation(libs.google.gson)
        implementation(libs.google.cloud.storage)
        implementation(libs.google.genai)
        implementation(libs.mcp)
        implementation(libs.opentelemetry.api)
        implementation(libs.kotlinx.coroutines.reactor)
        implementation(libs.slf4j.api)
        implementation(libs.snakeyaml)
        implementation(libs.google.flogger.extensions)
      }
    }
    val jvmTest by getting {
      dependsOn(commonJvmAndroidTest)
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.google.truth)
      }
    }
    val androidMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies {
        // Use standard Android genai or similar if available for OSS
        implementation(libs.google.genai.get().toString()) {
          exclude(group = "com.google.protobuf", module = "protobuf-java")
        } // Same as JVM if it's multiplatform, or use specific android one if separate.
        implementation(libs.google.protobuf.javalite)
        implementation(
          libs.google.auth.oauth2.http
        ) // Android compatible version or use separate for Android if needed.
        implementation(libs.google.mlkit.genai.prompt)
      }
    }
    val androidUnitTest by getting {
      dependsOn(commonJvmAndroidTest)
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

    val androidInstrumentedTest by getting {
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

android {
  namespace = "com.google.adk"

  // Reuse the test image bundled with the unit tests (androidUnitTest) in the instrumented
  // tests as well, so it gets packaged into the androidTest APK and is readable via the
  // instrumentation context's AssetManager.
  sourceSets { getByName("androidTest") { assets.srcDir("src/androidUnitTest/assets") } }

  // The instrumentation tests need compileSdk >= 35, so pass -PandroidCompileSdk=35:
  //   ./gradlew :google-adk-kotlin-core:assembleDebugAndroidTest -PandroidCompileSdk=35
  //   ./gradlew :google-adk-kotlin-core:connectedDebugAndroidTest -PandroidCompileSdk=35
  testOptions {

    // Injected into the generated test manifest (libraries have no
    // targetSdk). This avoids displaying a warning saying that the app
    // was built for an older version of android
    targetSdk = rootProject.extra["androidCompileSdk"] as Int

    unitTests {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
    }
  }

  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testApplicationId = "com.google.adk.kt.test"
  }

  // Required so the KMP `androidRelease` publication picks up sources. The
  // Dokka-backed javadoc jar is attached by the root build.gradle.kts.
  publishing { singleVariant("release") { withSourcesJar() } }
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
