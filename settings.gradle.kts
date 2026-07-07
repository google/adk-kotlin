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

pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "google-adk-kotlin"

include(":google-adk-kotlin-core")

project(":google-adk-kotlin-core").projectDir = file("core")

include(":google-adk-kotlin-testing")

project(":google-adk-kotlin-testing").projectDir = file("testing")

include(":google-adk-kotlin-processor")

project(":google-adk-kotlin-processor").projectDir = file("processor")

include(":google-adk-kotlin-webserver")

project(":google-adk-kotlin-webserver").projectDir = file("webserver")

include(":google-adk-kotlin-a2a")

project(":google-adk-kotlin-a2a").projectDir = file("a2a")

include(":google-adk-kotlin-firebase")

project(":google-adk-kotlin-firebase").projectDir = file("firebase")

include(":google-adk-kotlin-examples")

project(":google-adk-kotlin-examples").projectDir = file("examples")

include(":google-adk-kotlin-litertlm")

project(":google-adk-kotlin-litertlm").projectDir = file("litertlm")

include(":google-adk-kotlin-examples-android")

project(":google-adk-kotlin-examples-android").projectDir = file("examples/android")
