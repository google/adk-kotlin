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

// The litert-lm artifacts this module depends on are compiled for Java 21
// (class-file version 65), so this module must compile and run on a JDK 21+
// toolchain even though the rest of the project defaults to JDK 17. Honor a
// higher `jdkVersion` if one is explicitly requested.
val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()

kotlin {
  jvmToolchain(maxOf(21, jdkVersion))
  androidTarget { publishLibraryVariants("release") }
  jvm()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":google-adk-kotlin-core"))
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    val commonJvmAndroidMain by creating { dependsOn(commonMain) }
    val commonJvmAndroidTest by creating {
      dependsOn(commonTest)
      dependencies {
        implementation(libs.mockito.kotlin)
        implementation(libs.kotlinx.coroutines.test)
      }
    }
    val jvmMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies { implementation(libs.google.ai.edge.litertlm.jvm) }
    }
    val jvmTest by getting { dependsOn(commonJvmAndroidTest) }
    val androidMain by getting {
      dependsOn(commonJvmAndroidMain)
      dependencies { implementation(libs.google.ai.edge.litertlm.android) }
    }
    val androidUnitTest by getting { dependsOn(commonJvmAndroidTest) }
  }
}

android {
  namespace = "com.google.adk.litertlm"

  testOptions {
    targetSdk = rootProject.extra["androidCompileSdk"] as Int

    unitTests {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
    }
  }

  // Required so the KMP `androidRelease` publication picks up sources. The
  // Dokka-backed javadoc jar is attached by the root build.gradle.kts.
  publishing { singleVariant("release") { withSourcesJar() } }
}

// Coordinates the Kotlin Multiplatform plugin uses for the publications it
// auto-creates:
//   - `kotlinMultiplatform` -> google-adk-kotlin-litertlm         (root metadata)
//   - `jvm`                 -> google-adk-kotlin-litertlm-jvm     (KMP target)
//   - `androidRelease`      -> google-adk-kotlin-litertlm-android (KMP target)
// We only need to set the root publication's artifactId explicitly; per-target
// suffixes (`-jvm`, `-android`) are appended by the KMP plugin automatically.
// POM metadata, Dokka javadoc, and GPG signing are configured in the root
// build.gradle.kts.
publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform") {
      artifactId = "google-adk-kotlin-litertlm"
    }
  }
}
