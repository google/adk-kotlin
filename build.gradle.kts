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
  // Define plugins but do not apply them to the root project
  alias(libs.plugins.dokka)
  alias(libs.plugins.vanniktech.maven.publish) apply false
  kotlin("jvm") version "2.1.20" apply false
  kotlin("multiplatform") version "2.1.20" apply false
  id("com.android.library") version "8.13.0" apply false
  id("com.google.cloud.artifactregistry.gradle-plugin") version "2.2.4" apply false
  kotlin("plugin.serialization") version "2.1.20" apply false
}

val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()
val androidCompileSdk = providers.gradleProperty("androidCompileSdk").getOrElse("34").toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").getOrElse("26").toInt()

allprojects {
  group = "com.google.adk"
  version = "0.1.0"

  repositories {
    mavenCentral()
    google()
  }
}

subprojects {
  apply(plugin = "org.jetbrains.dokka")

  plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(jdkVersion)
    }
  }

  plugins.withId("org.jetbrains.kotlin.android") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
      jvmToolchain(jdkVersion)
    }
  }

  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
      jvmToolchain(jdkVersion)
    }
  }

  plugins.withId("com.android.library") {
    configure<com.android.build.gradle.LibraryExtension> {
      compileSdk = androidCompileSdk
      defaultConfig { minSdk = androidMinSdk }
    }
  }

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
  }

  // Publishing is configured once here for any subproject that applies the
  // vanniktech plugin (`com.vanniktech.maven.publish`). Per-module build files
  // are responsible only for declaring coordinates via `mavenPublishing {
  // coordinates(...) }`; POM metadata, signing, and the upload destination are
  // all handled centrally.
  //
  // Credentials and signing material are read from environment variables when
  // running on CI (see `.github/workflows/publish.yml`):
  //   - ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password
  //   - ORG_GRADLE_PROJECT_signingInMemoryKey  / ...KeyId / ...KeyPassword
  // For local publishing, the same values can be set as Gradle properties in
  // ~/.gradle/gradle.properties.
  plugins.withId("com.vanniktech.maven.publish") {
    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
      // Publish releases to Maven Central via the new Central Portal and let
      // Sonatype automatically release once validation passes. Snapshot
      // versions are routed to the Central snapshots repo automatically based
      // on the project version suffix.
      publishToMavenCentral(
        host = com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true,
      )
      signAllPublications()

      pom {
        name.set("Google Agent Development Kit")
        description.set("Google Agent Development Kit (ADK) for Kotlin")
        url.set("https://github.com/google/adk-kotlin")
        licenses {
          license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0")
          }
        }
        developers {
          developer {
            organization.set("Google Inc.")
            organizationUrl.set("https://www.google.com")
          }
        }
        scm {
          connection.set("scm:git:git@github.com:google/adk-kotlin.git")
          developerConnection.set("scm:git:git@github.com:google/adk-kotlin.git")
          url.set("https://github.com/google/adk-kotlin")
        }
      }
    }
  }
}
