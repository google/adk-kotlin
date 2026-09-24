---
name: adk-kotlin-setup
description: >-
  Sets up ADK Kotlin: either a consumer project that depends on the published com.google.adk artifacts (Gradle or Maven coordinates, KSP wiring for the @Tool processor, Kotlin / JDK / Android floors) or a contributor checkout of the adk-kotlin repository (prerequisites, build and test commands, source-set placement, CI checks, PR rules). Use when asked to add ADK to a project, wire the KSP processor, fix an "unresolved reference" to a generated tool class, align Kotlin or JDK versions, build or test the repository, or prepare a pull request. Don't use for writing agents and tools (use adk-kotlin-agent-builder).
---

# ADK Kotlin Setup

Checked against `main` after release 1.1.0 (September 2026).

This page gives rules, not version numbers. Current values live in `gradle/libs.versions.toml`, the root `build.gradle.kts`, `gradle.properties`, `settings.gradle.kts` and `.github/workflows/`. Read them before quoting a number. When this page and those files disagree, the files are right.

## Consumer setup

### Coordinates

All artifacts share the group `com.google.adk` and one release version (see the install snippet in `README.md`). Published artifact IDs:

- `google-adk-kotlin-core` for the runtime, and `google-adk-kotlin-processor` for `@Tool` code generation.
- `google-adk-kotlin-<module>` for the other JVM and multiplatform modules: `webserver`, `integrations`, `integrations-spring`, `a2a`, `litertlm`.
- The Android-only modules are published with an `-android` suffix: `google-adk-kotlin-firebase-android` and `google-adk-kotlin-mlkit-android`. ML Kit uses a `-beta` version of the shared release.

`testing` and the examples are not published. The `artifactId` in a module's `build.gradle.kts` is authoritative.

```kotlin
plugins {
  kotlin("jvm") version "<your Kotlin version>"
  id("com.google.devtools.ksp") version "<a KSP release that supports it>"
}

dependencies {
  implementation("com.google.adk:google-adk-kotlin-core:<adk version>")
  ksp("com.google.adk:google-adk-kotlin-processor:<adk version>")   // only for @Tool
}
```

Since KSP 2.3.0, KSP versions are independent of Kotlin versions; pick a KSP release that supports your compiler. Older KSP versions are named `<kotlin>-<ksp>` and must match the compiler exactly.

**Maven** cannot read Gradle Module Metadata. A module built with `kotlin("multiplatform")` (core among them) must be named by its JVM artifact, `google-adk-kotlin-<module>-jvm`; plain JVM modules have no suffix. The module's `build.gradle.kts` plugins block tells you which applies. Without KSP, use `ReflectiveTools.fromMethod` (see its KDoc) or hand-written tools.

### Floors

- **Kotlin 2.1 or newer.** Artifacts are compiled for language and API level 2.1 whatever compiler builds them (`kotlinCompatVersion` in the root build file).
- **JDK 17 or newer**; the LiteRT-LM module needs JDK 21.
- **Android:** `minSdk` and `compileSdk` are `androidMinSdk` / `androidCompileSdk` in the root build file.

### KSP wiring for `@Tool`

1. Put the processor on a `ksp` configuration, never `implementation`. In Kotlin Multiplatform use the per-target configurations (`kspJvm`, `kspAndroid`, `kspJvmTest`, and so on); `core/build.gradle.kts` shows the pattern.
2. Reference generated classes only from leaf source sets (`jvmMain`, `androidMain`, leaf test sets). `common*` source sets cannot see per-platform KSP output.

For `Unresolved reference` to a generated tool, check in order: the KSP plugin is applied, the processor is on the right `ksp*` configuration, the KSP version supports your Kotlin compiler, and the referencing file is not in a `common*` source set.

### Credentials

`Gemini` without an explicit key reads `GOOGLE_API_KEY` or `GEMINI_API_KEY`; Vertex AI takes explicit credentials. On Android, use the Firebase AI module instead of API keys.

## Contributor setup

### Prerequisites

- JDK 17 and JDK 21 installed (some modules force a 21 toolchain).
- An Android SDK matching `androidCompileSdk`. A full build needs it even for JVM-only changes.
- Always the Gradle wrapper, `./gradlew`.

`gradle.properties` gives the Gradle daemon a very large heap, and Kotlin compiles inside the daemon. On a smaller machine, override the daemon's heap:

```bash
./gradlew -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" build
```

`GRADLE_OPTS` only sizes the client JVM, and `-Pkotlin.daemon.jvmargs` does nothing with the in-process compiler.

### Build and test

Kotlin Multiplatform modules have no `test` task, so `./gradlew test` passes without running their tests. Use `build`:

| Change | Run |
| --- | --- |
| a module | `./gradlew :google-adk-kotlin-<module>:build` for it and every module that depends on it |
| `core` or `testing` | `./gradlew build` |
| KDoc | also `./gradlew dokkaGenerate` (it fails on any KDoc warning, and `build` does not run it) |
| one test class while iterating | `./gradlew :google-adk-kotlin-core:jvmTest --tests "<fully.qualified.Test>"`, then the module `build` |

`./gradlew :google-adk-kotlin-<module>:tasks --all` lists a module's test tasks. Integration suites that need credentials or local models skip themselves unless their environment variables are set.

### Where new code goes

Core is Kotlin Multiplatform with two custom intermediate source sets:

```text
commonMain                 platform-neutral runtime
└─ commonJvmAndroidMain    shared by JVM and Android
   ├─ jvmMain              JVM-only (MCP, Google Cloud clients)
   └─ androidMain          Android-only (Room, AppSearch)
```

- Platform-neutral code goes in `commonMain`, with no JVM or Android APIs.
- Code that needs `java.*`, OpenTelemetry or Reactive Streams goes in `commonJvmAndroidMain`.
- Code for one platform goes in `jvmMain` or `androidMain`.
- Tests mirror the source sets. Shared test fixtures go in the unpublished `testing` module.

### CI

`.github/workflows/` is authoritative. CI builds across a JDK matrix, runs `dokkaGenerate` separately, fails if the build dirtied the tree, and checks the PR's commit count and title.

### Code and PR rules

- Google Kotlin Style: two-space indent, 100 columns (`ktfmt --google-style`).
- Apache 2.0 header on every `.kt` and `.kts` file.
- New tests use `runBlocking` rather than `runTest`.
- **One commit per PR.** Amend and force-push with `--force-with-lease` instead of adding commits.
- **Conventional Commit PR title.** PRs are squash-merged and release-please reads the title: `feat:` bumps the minor version with a Features entry, `fix:` bumps the patch, and `docs:`, `test:`, `refactor:`, `chore:` release nothing. Choose by what the change is.
- adk-python is the source of truth for behaviour; align with it (`CONTRIBUTING.md`).
- Never hand-edit release versions; release-please maintains them.
