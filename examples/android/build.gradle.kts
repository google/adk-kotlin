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

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
  // Kotlin is compiled by AGP's built-in Kotlin support (no kotlin-android plugin).
  id("com.android.application")
}

// This sample transitively depends on androidx.core:core:1.16.0 (via the ML Kit GenAI / Gemini Nano
// stack), which requires compileSdk >= 35. Pin this example to at least 35 so it builds on its own
// without raising the repo-wide default (kept lower for the published libraries). core needs 35 the
// same way for its instrumentation tests (passed there via -PandroidCompileSdk=35).
val exampleCompileSdk = maxOf(35, rootProject.extra["androidCompileSdk"] as Int)

android {
  namespace = "com.google.adk.kt.examples.android"
  compileSdk = exampleCompileSdk

  defaultConfig {
    applicationId = "com.google.adk.kt.examples.android"
    minSdk = rootProject.extra["androidMinSdk"] as Int
    targetSdk = exampleCompileSdk
    versionCode = 1
    versionName = "0.1.0"

    // GEMINI_API_KEY is consumed by the SkillsAssetSourceActivity's manifest meta-data placeholder
    // (see the meta-data entry in src/main/AndroidManifest.xml). Sourced from a Gradle property or
    // environment variable; defaults to the literal placeholder so APKs built without a key
    // fail-fast with a user-visible message rather than calling Gemini with a blank key.
    manifestPlaceholders["GEMINI_API_KEY"] =
      providers
        .gradleProperty("GEMINI_API_KEY")
        .orElse(providers.environmentVariable("GEMINI_API_KEY"))
        .getOrElse("\${GEMINI_API_KEY}")
  }

  // The genai/auth transitive dependencies each ship a META-INF/INDEX.LIST and
  // META-INF/DEPENDENCIES, which collide when packaging the APK. Merge them, as
  // the root build does for the library modules.
  packaging {
    resources {
      merges += "**/META-INF/INDEX.LIST"
      merges += "**/META-INF/DEPENDENCIES"
      // The jakarta.* API jars (transitive via the A2A SDK) each ship duplicate license files.
      excludes += "**/META-INF/LICENSE.md"
      excludes += "**/META-INF/LICENSE-notice.md"
      excludes += "**/META-INF/NOTICE.md"
      excludes += "**/META-INF/beans.xml"
    }
  }
}

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(project(":google-adk-kotlin-a2a"))
  implementation(libs.google.mlkit.genai.prompt)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.core)
}

// a2a-java-sdk-common and -spec split the org.a2aproject.sdk.util package, so each ships its own
// package-info.class -> duplicate on the Android classpath. Strip it from the spec jar.
// Workaround for https://github.com/a2aproject/a2a-java/issues/978; remove once the a2a-java SDK
// no longer ships a duplicate package-info.class.
abstract class StripDuplicatePackageInfo : TransformAction<TransformParameters.None> {
  @get:Classpath @get:InputArtifact abstract val inputArtifact: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val input = inputArtifact.get().asFile
    if (!input.name.startsWith("a2a-java-sdk-spec")) {
      outputs.file(input)
      return
    }
    val output = outputs.file(input.name)
    ZipFile(input).use { zip ->
      ZipOutputStream(output.outputStream().buffered()).use { out ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          if (entry.name == "org/a2aproject/sdk/util/package-info.class") continue
          out.putNextEntry(ZipEntry(entry.name))
          zip.getInputStream(entry).use { it.copyTo(out) }
          out.closeEntry()
        }
      }
    }
  }
}

run {
  val artifactType = Attribute.of("artifactType", String::class.java)
  val stripped = Attribute.of("adk.stripped-package-info", Boolean::class.javaObjectType)
  dependencies {
    attributesSchema { attribute(stripped) }
    artifactTypes.getByName("jar") { attributes.attribute(stripped, false) }
    registerTransform(StripDuplicatePackageInfo::class) {
      from.attribute(stripped, false).attribute(artifactType, "jar")
      to.attribute(stripped, true).attribute(artifactType, "jar")
    }
  }
  configurations.configureEach { if (isCanBeResolved) attributes.attribute(stripped, true) }
}
