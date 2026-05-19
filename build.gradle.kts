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
  alias(libs.plugins.vanniktech.publish) apply false
}

val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()
val androidCompileSdk = providers.gradleProperty("androidCompileSdk").getOrElse("34").toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").getOrElse("26").toInt()
val snapshotUrl =
  findProperty("snapshotUrl") as? String
    ?: "https://central.sonatype.com/repository/maven-snapshots/"
val releaseUrl =
  findProperty("releaseUrl") as? String ?: "https://central.sonatype.com/api/v1/publisher"

allprojects {
  group = "com.google.adk"
  version = "0.1.0-rc.1"

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

  plugins.withId("com.vanniktech.maven.publish") {
    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
      publishToMavenCentral()

      val isRelease = !version.toString().endsWith("SNAPSHOT")
      val isPublishTask = gradle.startParameter.taskNames.any { it.contains("publish") && !it.contains("Local") }
      val signingKey = System.getenv("SIGNING_KEY")

      if (signingKey != null) {
        signAllPublications()
      } else if (isRelease && isPublishTask) {
        throw GradleException("Signing credentials are required for release versions. Please set SIGNING_KEY and SIGNING_PASSWORD environment variables.")
      }

      pom {
        name.set("Google Agent Development Kit")
        description.set("An open-source, code-first toolkit designed to simplify building, evaluating, and deploying advanced AI agents anywhere.")
        url.set("https://github.com/google/adk-kotlin")
        licenses {
          license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0")
          }
        }
        developers {
          developer {
            id.set("google")
            name.set("Google LLC")
            email.set("adk-java-repository-admins@google.com")
            organization.set("Google LLC")
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

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
  }
}
