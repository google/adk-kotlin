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
  // Kotlin is compiled by AGP's built-in Kotlin support (no kotlin-android plugin).
  id("com.android.application")
  // Generates the `@Tool` FunctionTools for the Firebase and LiteRT-LM examples.
  alias(libs.plugins.ksp)
  // Jetpack Compose compiler. Pinned (via the version catalog) to the Kotlin version AGP's built-in
  // Kotlin support uses, which the compiler plugin must match exactly.
  alias(libs.plugins.compose.compiler)
}

// Standard Firebase developer setup for the Firebase-backed examples (Skills, Firebase AI):
// the `com.google.gms.google-services` plugin reads a `google-services.json` from this module's
// root and generates the default FirebaseApp so `FirebaseApp.getInstance()` works with zero code.
// It is applied only when a developer drops their own file in; when absent the app still builds
// and those examples fall back to the FIREBASE_* env vars (see FirebaseAppResolver). See README.
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
}

// Build-time fallback config keys for the Firebase-backed examples, read as Gradle properties or
// env vars. Shared by the manifest-placeholder loop and the "no configuration" diagnostic below.
val firebaseConfigKeys = listOf("FIREBASE_API_KEY", "FIREBASE_APP_ID", "FIREBASE_PROJECT_ID")

android {
  namespace = "com.google.adk.kt.examples.android"

  // 37: `core` declares minCompileSdk=37, which AGP enforces transitively on this app.
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.google.adk.kt.examples.android"
    minSdk = rootProject.extra["androidMinSdk"] as Int
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Fallback Firebase-config path for the Firebase-backed examples (see FirebaseAppResolver).
    // When no google-services.json is present, the Firebase config can instead be baked into the
    // APK at build time as manifest metadata, sourced from Gradle properties
    // (-PFIREBASE_API_KEY=...) or the matching environment variables.
    for (name in firebaseConfigKeys) {
      manifestPlaceholders[name] =
        providers
          .gradleProperty(name)
          .orElse(providers.environmentVariable(name))
          .getOrElse("\${$name}")
    }
  }

  buildFeatures { compose = true }

  // The genai/auth and Firebase transitive dependencies each ship a META-INF/INDEX.LIST and
  // META-INF/DEPENDENCIES, which collide when packaging the APK. Merge them, as the root build does
  // for the library modules.
  packaging {
    resources {
      merges += "**/META-INF/INDEX.LIST"
      merges += "**/META-INF/DEPENDENCIES"
    }
  }
}

// Build-time diagnostic for the Firebase-backed examples: if neither a google-services.json nor a
// complete set of FIREBASE_* values is present, the produced APK has no usable Firebase config, so
// those examples show a "no configuration" message at runtime.
run {
  val hasGoogleServicesJson = file("google-services.json").exists()
  val missingKeys = firebaseConfigKeys.filter { name ->
    providers
      .gradleProperty(name)
      .orElse(providers.environmentVariable(name))
      .orNull
      .isNullOrBlank()
  }
  if (!hasGoogleServicesJson && missingKeys.isNotEmpty()) {
    val projectPath = project.path
    val log = logger
    val message =
      "examples/android: building without a usable Firebase configuration. The app will build " +
        "and launch, but its Firebase-backed examples (Skills, Firebase AI) will show a \"No " +
        "Firebase configuration found\" message instead of calling Firebase. Add a " +
        "google-services.json to examples/android/, or pass -PFIREBASE_API_KEY=... " +
        "-PFIREBASE_APP_ID=... -PFIREBASE_PROJECT_ID=... " +
        "(missing: ${missingKeys.joinToString()}). See examples/android/README.md."
    // Fire only when this app's build/install tasks are actually scheduled (not on every Gradle
    // configuration).
    gradle.taskGraph.addTaskExecutionGraphListener { graph ->
      val buildingThisApp =
        graph.allTasks.any { task ->
          task.path.startsWith("$projectPath:") &&
            listOf("assemble", "install", "bundle", "package").any { task.name.startsWith(it) }
        }
      if (buildingThisApp) log.warn("WARNING: $message")
    }
  }
}

dependencies {
  implementation(project(":google-adk-kotlin-core"))
  implementation(project(":google-adk-kotlin-firebase"))
  implementation(project(":google-adk-kotlin-litertlm"))
  implementation(project(":google-adk-kotlin-mlkit"))
  implementation(platform(libs.google.firebase.platform))
  implementation(libs.google.firebase.ai)
  // The LiteRT-LM example builds its own EngineConfig, and the module's own dependency on the
  // runtime is `implementation`, so it is not exposed transitively.
  implementation(libs.google.ai.edge.litertlm.android)
  implementation(libs.google.mlkit.genai.prompt)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.core)

  // Jetpack Compose UI (Material 3). The BOM aligns all Compose artifact versions.
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)

  // Generates the `@Tool` FunctionTools for the Firebase and LiteRT-LM examples.
  ksp(project(":google-adk-kotlin-processor"))
}
