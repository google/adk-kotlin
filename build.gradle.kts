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
  kotlin("jvm") version "2.1.20" apply false
  kotlin("multiplatform") version "2.1.20" apply false
  id("com.android.library") version "8.13.0" apply false
  id("com.google.cloud.artifactregistry.gradle-plugin") version "2.2.4" apply false
  kotlin("plugin.serialization") version "2.1.20" apply false
}

val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()
val androidCompileSdk = providers.gradleProperty("androidCompileSdk").getOrElse("34").toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").getOrElse("26").toInt()

// Expose the resolved Android SDK levels so subproject build scripts can reuse
// them as a single source of truth (e.g. `core` sets `targetSdk` from this).
extra["androidCompileSdk"] = androidCompileSdk

extra["androidMinSdk"] = androidMinSdk

allprojects {
  group = "com.google.adk"
  version = "0.2.1-SNAPSHOT" // x-release-please-version

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

      packaging {
        resources {
          merges += "**/META-INF/INDEX.LIST"
          merges += "**/META-INF/DEPENDENCIES"
        }
      }
    }
  }

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
  }

  // Publishing is configured once here for any subproject that applies
  // Gradle's built-in `maven-publish` plugin. Per-module build files set the
  // `artifactId` on the publications the Kotlin / Android plugins auto-create
  // (see core/, a2a/, firebase/, processor/, webserver/). POM metadata,
  // Dokka-fed javadoc jars, and GPG signing are configured centrally here.
  plugins.withId("maven-publish") {
    apply(plugin = "signing")

    // Single Dokka-backed `-javadoc.jar`, attached to every JVM/KMP
    // publication this project produces. AGP's Android single-variant
    // publication (the firebase module) ships its own (empty-ish) javadoc
    // jar via `withJavadocJar()`; we skip the Dokka one there to avoid a
    // duplicate-artifact error at publish time.
    val dokkaJavadocJar =
      tasks.register<Jar>("dokkaJavadocJar") {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaHtml"))
      }

    configure<PublishingExtension> {
      publications.withType<MavenPublication>().configureEach {
        if (name != "release") {
          artifact(dokkaJavadocJar)
        }

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

    configure<SigningExtension> {
      val signingKey: String? = providers.gradleProperty("signingInMemoryKey").orNull
      val signingKeyId: String? = providers.gradleProperty("signingInMemoryKeyId").orNull
      val signingPassword: String? = providers.gradleProperty("signingInMemoryKeyPassword").orNull

      if (signingKey != null) {
        if (signingKeyId != null) {
          useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else {
          useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(extensions.getByType<PublishingExtension>().publications)
      }
    }

    // Work around a known Gradle quirk where Kotlin Multiplatform creates
    // multiple publications per project but Gradle's task graph does not
    // automatically wire each `publishXxxPublicationTo...` task to its
    // corresponding `signXxxPublication`. Without this, parallel publish
    // tasks observe each other's unsigned artifacts and Gradle errors out
    // with an "implicit dependency" validation problem. See
    // https://github.com/gradle/gradle/issues/26091
    tasks.withType<AbstractPublishToMaven>().configureEach {
      val signingTasks = tasks.withType<Sign>()
      mustRunAfter(signingTasks)
    }
  }
}
