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
  // Generates the `@Tool` FunctionTools used by the Firebase example
  // (WeatherTools.generatedTools()).
  alias(libs.plugins.ksp)
}

// Standard Firebase developer setup for the Firebase example: the `com.google.gms.google-services`
// plugin reads a `google-services.json` from this module's root and generates the default
// FirebaseApp configuration so `FirebaseApp.getInstance()` works with zero code. The plugin is
// applied only when a developer has dropped their own file in. When it is absent the app still
// builds and the Firebase example falls back to the FIREBASE_* environment variables (see
// FirebaseChatActivity). Read README.md for setup.
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
}

// The build-time fallback config keys for the Firebase example, read as Gradle properties or
// environment variables. Shared by the manifest-placeholder loop and the "no configuration"
// diagnostic below.
val firebaseConfigKeys = listOf("FIREBASE_API_KEY", "FIREBASE_APP_ID", "FIREBASE_PROJECT_ID")

android {
  namespace = "com.google.adk.kt.examples.android"

  // compileSdk 36.1: the Firebase example depends on the Firebase AI SDK (via
  // :google-adk-kotlin-firebase), which is built against 36.1, and the ML Kit GenAI / Gemini Nano
  // stack pulls in androidx.core:core:1.16.0, which needs compileSdk >= 35. 36.1 satisfies both.
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.google.adk.kt.examples.android"
    minSdk = rootProject.extra["androidMinSdk"] as Int
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Fallback Firebase-config path for the Firebase example (see FirebaseChatActivity): when no
    // google-services.json is present, the Firebase config can instead be baked into the APK at
    // build time as manifest metadata, sourced from Gradle properties (-PFIREBASE_API_KEY=...) or
    // the matching environment variables.
    for (name in firebaseConfigKeys) {
      manifestPlaceholders[name] =
        providers
          .gradleProperty(name)
          .orElse(providers.environmentVariable(name))
          .getOrElse("\${$name}")
    }
  }

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

// Build-time diagnostic for the Firebase example: if neither a google-services.json nor a complete
// set of FIREBASE_* values is present, the produced APK has no usable Firebase config, so the
// Firebase example shows a "no configuration" message at runtime.
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
        "and launch, but its Firebase example will show a \"No Firebase configuration found\" " +
        "message instead of calling Firebase. Add a google-services.json to examples/android/, " +
        "or pass -PFIREBASE_API_KEY=... -PFIREBASE_APP_ID=... -PFIREBASE_PROJECT_ID=... " +
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
  implementation(project(":google-adk-kotlin-mlkit"))
  implementation(platform(libs.google.firebase.platform))
  implementation(libs.google.firebase.ai)
  implementation(libs.google.mlkit.genai.prompt)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.core)

  // Generates the `@Tool` FunctionTools used by the Firebase example
  // (WeatherTools.generatedTools()).
  ksp(project(":google-adk-kotlin-processor"))
}
