---
name: adk-kotlin-setup
description: >-
  Sets up ADK Kotlin: either a consumer project that depends on the published com.google.adk artifacts (Gradle or Maven coordinates, KSP wiring for the @Tool processor, Kotlin / JDK / Android version floors) or a contributor checkout of the adk-kotlin repository (JDK matrix, Android SDK, Gradle properties, test and run commands, single-commit PR rules). Use when asked to add ADK to a project, wire the KSP processor, fix an "unresolved reference" to a generated tool class, align Kotlin or JDK versions, bootstrap or repair a checkout, run the tests, or prepare a pull request. Don't use for explaining how the runtime works (use adk-kotlin-architecture) or for writing agents and tools (use adk-kotlin-agent-builder).
---

# ADK Kotlin Setup

Two audiences: a **consumer** adding ADK to their own Kotlin project, and a **contributor** building this repository. Read only the half you need.

Every version below was read from `build.gradle.kts`, `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` in this repository. Re-check those files before quoting a number; they move with every release.

## Consumer setup

### Coordinates

All modules share the group `com.google.adk` and one version. This page deliberately pins no version number, because release-please does not update it here; take the current release from the install snippet in `README.md`, from `VERSION` in `core/src/commonMain/kotlin/com/google/adk/kt/Version.kt`, or from the Maven Central badge.

| Need | Gradle artifact | Notes |
|---|---|---|
| Agents, models, tools, sessions, runners | `google-adk-kotlin-core` | Kotlin Multiplatform root; Gradle picks the `-jvm` or `-android` variant. |
| `@Tool` code generation | `google-adk-kotlin-processor` | Add on the `ksp` configuration, not `implementation`. |
| HTTP serving and the Dev UI | `google-adk-kotlin-webserver` | JVM only. |
| BigQuery analytics plugin and other integrations | `google-adk-kotlin-integrations` | JVM only. |
| Agent2Agent remote agents | `google-adk-kotlin-a2a` | JVM only. |
| On-device LiteRT-LM models | `google-adk-kotlin-litertlm` | Needs a JDK 21 toolchain. |
| Firebase AI on Android | `google-adk-kotlin-firebase-android` | Android AAR only. |
| Gemini Nano through ML Kit | `google-adk-kotlin-mlkit-android` | Android AAR, `-beta` suffix on the version. |

Gradle:

```kotlin
plugins {
  kotlin("jvm") version "2.1.20"          // any 2.1+ compiler works, see the floors below
  kotlin("plugin.serialization") version "2.1.20"
  id("com.google.devtools.ksp") version "2.1.20-1.0.31"  // must match your Kotlin version
}

val adkVersion = "..."   // the release shown in README.md

dependencies {
  implementation("com.google.adk:google-adk-kotlin-core:$adkVersion")
  ksp("com.google.adk:google-adk-kotlin-processor:$adkVersion")   // only if you use @Tool
}
```

Maven cannot read Gradle Module Metadata, so it must name the JVM target artifact directly. The root coordinate resolves to nothing usable from Maven:

```xml
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk-kotlin-core-jvm</artifactId>
  <version>${adk.version}</version>
</dependency>
```

The same `-jvm` suffix applies to `a2a`, `integrations` and `litertlm` from Maven. `webserver` and `processor` are plain JVM jars with no suffix. This repository ships no Maven recipe for running KSP; Maven users who want `@Tool` generation need the `kotlin-maven-plugin` KSP integration, which is not documented here. Without KSP they can still hand-write `FunctionTool` subclasses (see the agent-builder skill).

### Version floors a consumer must meet

| Constraint | Value | Where it comes from |
|---|---|---|
| Kotlin compiler | 2.1 or newer | Artifacts are compiled with Kotlin 2.3.21 but emit language and API level 2.1 (`kotlinCompatVersion` in the root build file) with `kotlin-stdlib` pinned to 2.1.20 (`kotlinCoreLibrariesVersion`). |
| JVM | 17 or newer | `jdkVersion` defaults to 17 and drives every `jvmToolchain`. |
| LiteRT-LM and the examples | JDK 21 | The upstream `litert-lm` jars are class-file version 65. |
| Android `minSdk` | 26 | `androidMinSdk`. |
| Android `compileSdk` | 37 | `androidCompileSdk`; `androidx.appfunctions` requires it and AGP enforces it transitively on apps that depend on core. |
| `kotlinx-serialization` | present | Core's `commonMain` uses `kotlinx-serialization-json`; apply the serialization plugin if you declare `@Serializable` tool payloads. |
| GenAI SDK | transitive `api` dependency | `com.google.genai:google-genai-kotlin` is exposed as `api` from core, so its `Client` is on your classpath for `Gemini(client, name)` without a separate declaration. ADK's own `Content` and `Part` live in `com.google.adk.kt.types`. |

### KSP wiring for `@Tool`

The processor reads `com.google.adk.kt.annotations.Tool` and writes a `FunctionTool` subclass with KotlinPoet. Three facts decide whether the generated class is visible:

1. **Configuration name.** Plain Kotlin/JVM: `ksp(...)`. Kotlin Multiplatform: the per-target names `kspJvm`, `kspAndroid`, and for test source sets `kspJvmTest` / `kspAndroidHostTest` (the pattern core itself uses in `core/build.gradle.kts`).
2. **Where generated sources may be referenced.** KMP forbids a `common*` source set from referencing per-platform KSP output. Put the code that instantiates a generated tool in a leaf source set (`jvmMain`, `androidMain`, or a `srcDir` shared by leaf test sets, which is what `core/src/jvmAndroidKspTest/kotlin` does).
3. **Processor arguments.** The processor reads none. A `ksp { arg(...) }` block in core is for Room, not for this processor.

Symptom: `Unresolved reference: MyFunctionTool` after adding `@Tool`. Check in order: the `ksp` plugin is applied, the processor is on the right `ksp*` configuration, the KSP plugin version matches the Kotlin plugin version, and the referencing file is not in `commonMain`.

The catalog in this repo lists `ksp = "2.3.9"` while the processor module compiles against `symbol-processing-api:1.9.23-1.0.19`. That mismatch is tolerated by KSP's API stability, so do not "fix" it in a consumer project by downgrading the KSP plugin; match the KSP plugin to your Kotlin compiler.

### Credentials at runtime

`Gemini(name = "...")` with no `apiKey` argument falls back to the `GOOGLE_API_KEY` or `GEMINI_API_KEY` environment variable inside the GenAI SDK. `Gemini(name, vertexCredentials = VertexCredentials(...))` targets Vertex AI. Neither API keys nor `GoogleCredentials` work in the GenAI SDK on Android; use the Firebase AI module there.

The only environment variable core itself reads is `ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS` (`true` or `1` puts prompt and response content on OpenTelemetry spans).

## Contributor setup

### Prerequisites

- JDK 17 plus JDK 21. CI runs the matrix `17`, `21`, `25` and always installs 21 alongside because `litertlm`, `examples` and `examples/java` force a 21+ toolchain. The Foojay resolver plugin in `settings.gradle.kts` can download missing toolchains if you let it.
- Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`. `core`, `testing` and `litertlm` have Android targets, and `firebase`, `mlkit` and the Android example are Android modules, so a full `./gradlew build` needs the SDK even when your change is JVM-only. `integrations` and `a2a` are JVM-only multiplatform modules.
- Gradle wrapper 9.7.1 (`./gradlew`, never a system Gradle).
- No `google-services.json` is required. `examples/android` applies the Google Services plugin only when that file exists.

`gradle.properties` sets `-Xmx32g` for Gradle and compiles Kotlin in-process. On a machine with less memory, override on the command line the way CI does:

```bash
export GRADLE_OPTS="-Xmx4g -XX:MaxMetaspaceSize=1g"
./gradlew -Pkotlin.daemon.jvmargs="-Xmx4g" build
```

### Gradle properties

| Property | Default | Effect |
|---|---|---|
| `-PjdkVersion` | `17` | Toolchain for every module; `litertlm` and the examples clamp to `max(21, jdkVersion)`. |
| `-PandroidCompileSdk` | `37` | Passed to every Android target. |
| `-PandroidMinSdk` | `26` | Passed to every Android target. |
| `-PsigningInMemoryKey`, `-PsigningInMemoryKeyId`, `-PsigningInMemoryKeyPassword` | unset | GPG signing for publishing; skipped when the key is absent. |
| `-PFIREBASE_API_KEY`, `-PFIREBASE_APP_ID`, `-PFIREBASE_PROJECT_ID` | unset | Baked into the Android example app when no `google-services.json` exists. |

### Key commands

| Task | Command |
|---|---|
| What CI runs | `./gradlew -PjdkVersion=17 build` (also with 21 and 25) |
| One module's JVM tests | `./gradlew :google-adk-kotlin-core:jvmTest` |
| One test class | `./gradlew :google-adk-kotlin-core:jvmTest --tests "com.google.adk.kt.agents.LlmAgentTest"` |
| Plain-JVM modules (`webserver`, `processor`) | `./gradlew :google-adk-kotlin-webserver:test` |
| Android host (Robolectric) tests for core | `./gradlew :google-adk-kotlin-core:tasks --all \| grep -i hosttest` to find the task name the AGP KMP plugin generated, then run it |
| Serve the example agents with the Dev UI | `GOOGLE_API_KEY=... ./gradlew :google-adk-kotlin-examples:runServer --args="--dev"` then open `http://localhost:8080/dev-ui` |
| Run a Java example | `./gradlew :google-adk-kotlin-examples-java:runJavaExample --args="com.google.adk.kt.examples.hello.HelloAgentJava"` |
| Install the Android example app | `./gradlew :google-adk-kotlin-examples-android:installDebug` |
| API docs | `./gradlew dokkaGeneratePublicationHtml` |
| Publish locally for a consumer test | `./gradlew publishToMavenLocal` (repositories already include `mavenLocal()`) |

Module Gradle paths are `:google-adk-kotlin-<dir>`; `settings.gradle.kts` maps each to its directory (`core`, `processor`, `testing`, `webserver`, `integrations`, `a2a`, `firebase`, `mlkit`, `litertlm`, `examples`, `examples/java`, `examples/android`).

### Integration tests are gated by environment

| Suite | Runs when |
|---|---|
| MCP (`*IntegrationTest` under `core/src/jvmTest/.../tools/mcp/it`) | `ADK_MCP_DISABLE_IT` is unset or falsy; live-Gemini cases also need `GOOGLE_API_KEY`. Retried up to 2 times by the test-retry plugin. |
| Firebase (`firebase/src/test/.../it`) | `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID` set and `FIREBASE_DISABLE_IT` falsy. |
| LiteRT-LM (`litertlm/src/jvmTest/.../it`) | `LITERT_LM_MODEL_PATH` (or `-Dlitert_lm_model_path`) points at a `.litertlm` file. Never runs in CI. |

A plain `./gradlew build` with none of these set is green; the suites skip themselves with JUnit assumptions.

### Source-set layout to respect

Core is KMP with two custom intermediate source sets, which is why `kotlin.mpp.applyDefaultHierarchyTemplate=false` is in `gradle.properties`:

```
commonMain            platform-neutral runtime (agents, tools, sessions, models)
├─ commonJvmAndroidMain  JVM and Android shared: interop bridges, OTel, FileArtifactService
│  ├─ jvmMain            MCP, GCS, Vertex sessions/memory, Flogger logging
│  └─ androidMain        Room sessions, AppSearch memory, AppFunctions tools
commonTest / commonJvmAndroidTest / jvmTest / androidHostTest / androidDeviceTest
```

New platform-neutral code goes in `commonMain`. Anything touching `java.*` beyond what Kotlin stdlib exposes goes in `commonJvmAndroidMain` or lower. `expect`/`actual` pairs are listed in the architecture skill.

### Formatting and PR rules

- Google Kotlin Style, two-space indent, 100 columns. No formatter is configured in the build; CI only fails if the build itself dirties the tree (`git diff --exit-code`). Run `ktfmt --google-style` locally before committing if you have it.
- Apache 2.0 header on every `.kt` and `.kts` file (copy from a neighbour).
- **One commit per PR.** `pr-commit-check.yml` fails on more than one; amend and force-push with `--force-with-lease` instead of adding commits.
- **Conventional Commit PR title.** `pr-title-check.yml` enforces `<type>[(scope)][!]: <description>` with type in `build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test`, non-empty description, no trailing period. PRs are squash-merged, so the title becomes the commit release-please reads for the changelog.
- adk-python is the source of truth for behaviour; when Kotlin and Python disagree, `CONTRIBUTING.md` says to align with Python and cite it.
- Release versions live in three places kept in sync by release-please markers: `build.gradle.kts` (`x-release-please-version`), `Version.kt` (`x-release-please-released-version`) and the README install snippet. Never hand-edit them.
