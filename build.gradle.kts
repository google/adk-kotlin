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
  kotlin("jvm") version "2.3.21" apply false
  kotlin("multiplatform") version "2.3.21" apply false
  id("com.android.library") version "9.3.1" apply false
  id("com.android.application") version "9.3.1" apply false
  id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
  id("com.google.cloud.artifactregistry.gradle-plugin") version "2.2.4" apply false
  kotlin("plugin.serialization") version "2.3.21" apply false
  alias(libs.plugins.gradle.test.retry) apply false
  alias(libs.plugins.google.services) apply false
  // Compose compiler plugin, pinned to AGP's built-in Kotlin version (see examples/android).
  alias(libs.plugins.compose.compiler) apply false
}

val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()
// Kotlin language and API level for emitted metadata/bytecode. Pinned below the
// compiler version so published artifacts stay consumable by projects on this
// Kotlin version and downstream apps aren't forced to upgrade.
val kotlinCompatVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1
// kotlin-stdlib version the Kotlin plugin adds and publishes transitively. Pinned
// to a 2.1 release so its metadata stays readable by consumers on the Kotlin 2.1
// compiler; without this the 2.3 compiler would leak a 2.3 stdlib into the POMs
// and break the kotlinCompatVersion promise above.
val kotlinCoreLibrariesVersion = "2.1.20"
// API 37: androidx.appfunctions requires minCompileSdk=37 (minSdk unchanged).
val androidCompileSdk = providers.gradleProperty("androidCompileSdk").getOrElse("37").toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").getOrElse("26").toInt()

// Expose the resolved Android SDK levels so subproject build scripts can reuse
// them as a single source of truth (e.g. `core` sets `targetSdk` from this).
extra["androidCompileSdk"] = androidCompileSdk

extra["androidMinSdk"] = androidMinSdk

// Shared so Android modules on AGP's built-in Kotlin (which don't apply the
// kotlin.jvm/multiplatform plugins configured below) can reuse the same pin.
extra["kotlinCoreLibrariesVersion"] = kotlinCoreLibrariesVersion

// Versions for the Dokka `externalDocumentationLinks` URLs below, read from the catalog so a
// dependency bump carries the doc link with it. Resolved here because the type-safe `libs`
// accessors do not resolve inside `subprojects {}`, where those links are configured.
val docsAuthVersion = libs.versions.google.auth.get()
val docsOtelVersion = libs.versions.opentelemetry.get()
val docsGcsVersion = libs.versions.google.cloud.storage.get()
val docsBigQueryVersion = libs.versions.google.cloud.bigquery.get()
val docsSlf4jVersion = libs.versions.slf4j.get()
val docsFloggerVersion = libs.versions.flogger.get()

allprojects {
  group = "com.google.adk"
  version = "1.0.1-SNAPSHOT" // x-release-please-version

  repositories {
    mavenCentral()
    google()
    mavenLocal()
  }
}

subprojects {
  apply(plugin = "org.jetbrains.dokka")

  // Dokka renders a reference as a plain span unless an `externalDocumentationLinks` entry gives
  // it a URL; only kotlin-stdlib, the JDK and Android are on by default. Javadoc 9+ publishes
  // `element-list`, not the `package-list` Dokka assumes, so the Java entries name it explicitly.
  // A list URL that stops resolving leaves its entry silently inert, so re-check them on a bump.
  configure<org.jetbrains.dokka.gradle.DokkaExtension> {
    dokkaSourceSets.configureEach {
      fun link(name: String, base: String, list: String = "package-list") {
        externalDocumentationLinks.register(name) {
          url(base)
          packageListUrl("$base$list")
        }
      }

      // The four entries below keep literal URLs: the kotlinlang.org ones carry no version,
      // error-prone uses javadoc.io's moving `latest/`, and reactive-streams arrives
      // transitively via kotlinx-coroutines-reactive with no catalog entry of its own.
      link("kotlinx-coroutines", "https://kotlinlang.org/api/kotlinx.coroutines/")
      link("kotlinx-serialization", "https://kotlinlang.org/api/kotlinx.serialization/")
      link(
        "error-prone-annotations",
        "https://javadoc.io/doc/com.google.errorprone/error_prone_annotations/latest/",
        "element-list",
      )
      link(
        "reactive-streams",
        "https://www.reactive-streams.org/reactive-streams-1.0.4-javadoc/",
        "element-list",
      )
      link(
        "google-auth-oauth2",
        "https://javadoc.io/doc/com.google.auth/google-auth-library-oauth2-http/$docsAuthVersion/",
        "element-list",
      )
      link(
        "google-auth-credentials",
        "https://javadoc.io/doc/com.google.auth/google-auth-library-credentials/$docsAuthVersion/",
        "element-list",
      )
      link(
        "opentelemetry-api",
        "https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/$docsOtelVersion/",
        "element-list",
      )
      link(
        "opentelemetry-sdk-common",
        "https://javadoc.io/doc/io.opentelemetry/opentelemetry-sdk-common/$docsOtelVersion/",
        "element-list",
      )
      link(
        "opentelemetry-sdk-trace",
        "https://javadoc.io/doc/io.opentelemetry/opentelemetry-sdk-trace/$docsOtelVersion/",
        "element-list",
      )
      link(
        "google-cloud-storage",
        "https://javadoc.io/doc/com.google.cloud/google-cloud-storage/$docsGcsVersion/",
        "element-list",
      )
      link(
        "google-cloud-bigquery",
        "https://javadoc.io/doc/com.google.cloud/google-cloud-bigquery/$docsBigQueryVersion/",
        "element-list",
      )
      link("slf4j", "https://javadoc.io/doc/org.slf4j/slf4j-api/$docsSlf4jVersion/", "element-list")
      link(
        "flogger",
        "https://javadoc.io/doc/com.google.flogger/google-extensions/$docsFloggerVersion/",
        "element-list",
      )
    }
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(jdkVersion)
      coreLibrariesVersion = kotlinCoreLibrariesVersion
    }
  }

  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
      jvmToolchain(jdkVersion)
      coreLibrariesVersion = kotlinCoreLibrariesVersion
    }

    // Dokka analyzes `common*` as JVM but hands it unreadable `.klib` metadata, so KDoc links into
    // dependencies do not resolve (Kotlin/dokka#3137). Every KMP module here has a `jvm` target,
    // whose jar classpath is the right stand-in; revisit if an Android-only or non-JVM one lands.
    val kmp = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
    val jvmCompileClasspath = provider {
      kmp.targets.getByName("jvm").compilations.getByName("main").compileDependencyFiles
    }
    configure<org.jetbrains.dokka.gradle.DokkaExtension> {
      dokkaSourceSets.configureEach {
        if (name.startsWith("common")) {
          classpath.from(jvmCompileClasspath)
        }
      }
    }
  }

  // AGP built-in Kotlin: the Android modules no longer apply kotlin-android, so
  // pin the Java/Kotlin target via `compileOptions` (Kotlin's jvmTarget follows
  // `targetCompatibility`).
  val androidJavaVersion = JavaVersion.toVersion(jdkVersion)

  plugins.withId("com.android.library") {
    configure<com.android.build.api.dsl.LibraryExtension> {
      compileSdk = androidCompileSdk
      defaultConfig { minSdk = androidMinSdk }

      compileOptions {
        sourceCompatibility = androidJavaVersion
        targetCompatibility = androidJavaVersion
      }

      packaging {
        resources {
          merges += "**/META-INF/INDEX.LIST"
          merges += "**/META-INF/DEPENDENCIES"
        }
      }
    }
  }

  plugins.withId("com.android.application") {
    configure<com.android.build.api.dsl.ApplicationExtension> {
      compileOptions {
        sourceCompatibility = androidJavaVersion
        targetCompatibility = androidJavaVersion
      }
    }
  }

  // Conscrypt, which Robolectric pulls in, calls System::load, which JDK 24 restricts (JEP 472).
  // Keyed off the JVM that runs the tests, not the jdkVersion property, because the Android
  // modules apply no Kotlin toolchain. The flag is not needed below 24.
  tasks.withType<Test>().configureEach {
    val launcher = javaLauncher
    jvmArgumentProviders.add(
      CommandLineArgumentProvider {
        if (launcher.get().metadata.languageVersion.asInt() >= 24) {
          listOf("--enable-native-access=ALL-UNNAMED")
        } else {
          emptyList()
        }
      }
    )
  }

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      optIn.add("kotlin.time.ExperimentalTime")
      // `explicitNulls` in adkJson and `@EncodeDefault` on FunctionCall / FunctionResponse are
      // experimental, and the timing tests read `currentTime`.
      optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
      optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
      freeCompilerArgs.add("-Xskip-metadata-version-check")
      // `expect`/`actual` classes are Beta (KT-61573); GoogleCredentials relies on them.
      freeCompilerArgs.add("-Xexpect-actual-classes")
      // Compile with the current Kotlin toolchain but emit metadata/bytecode
      // for kotlinCompatVersion so downstream consumers aren't forced to upgrade.
      languageVersion.set(kotlinCompatVersion)
      apiVersion.set(kotlinCompatVersion)
    }
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
    // publication (the firebase module) ships its own javadoc jar via
    // `withJavadocJar()`; we skip the Dokka one there to avoid a
    // duplicate-artifact error at publish time.
    val dokkaJavadocJar =
      tasks.register<Jar>("dokkaJavadocJar") {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaGeneratePublicationHtml"))
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
