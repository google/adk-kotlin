# ADK Kotlin — Android examples

A single installable Android app that collects the ADK Kotlin Android examples
behind a launcher menu
([`HomeActivity`](src/main/kotlin/com/google/adk/kt/examples/android/home/HomeActivity.kt)).
Each menu entry opens one self-contained example `Activity`:

-   **Room session** — an on-device agent whose conversation is persisted across
    restarts by the Room-backed session service.
-   **Skills (AssetSkillSource)** — an agent whose `SkillToolset` reads skill
    definitions from the APK's `assets/skills/...` tree, backed by the cloud
    Firebase AI (Gemini) model so its function calling reliably drives the
    toolset.
-   **ML Kit chat** — a multi-turn chat with an on-device Gemini Nano agent,
    with a streaming toggle.
-   **Firebase AI** — a chat backed by the cloud Firebase AI (Gemini) model from
    the [`:google-adk-kotlin-firebase`](../../firebase) module, demonstrating
    both plain chat and tool calling (via
    [`WeatherTools.kt`](src/main/kotlin/com/google/adk/kt/examples/android/firebase/WeatherTools.kt)).

**Room session** and **ML Kit chat** run fully on-device through ML Kit's Gemini
Nano, so they need no API key or network (the first run may download Gemini
Nano). The **Skills (AssetSkillSource)** and **Firebase AI** examples call the
cloud Firebase backend, so they need a Firebase configuration and network access
— see [Configure Firebase](#configure-firebase-for-the-firebase-backed-examples)
below.

## Build & run

With a device or emulator connected:

```shell
./gradlew :google-adk-kotlin-examples-android:installDebug
```

Then launch **"ADK Android Examples"** from the launcher and pick an example.

## Configure Firebase (for the Firebase-backed examples)

The **Skills (AssetSkillSource)** and **Firebase AI** examples need to know
which Firebase project to talk to; the on-device examples (Room session, ML Kit
chat) don't need any of this. Two ways, in order of preference:

### 1. `google-services.json` (standard Firebase setup — recommended)

This mirrors what a normal Firebase Android developer does.

1.  In the [Firebase console](https://console.firebase.google.com/), open your
    project (or create one) and enable the **Firebase AI Logic** / Gemini API.
2.  Register an **Android app** with the package name
    **`com.google.adk.kt.examples.android`** (this is the `applicationId` in
    [`build.gradle.kts`](build.gradle.kts); change both if you prefer your own).
3.  Download the generated `google-services.json` and place it in **this
    directory** (`examples/android/google-services.json`).

This file just points the app at a specific Firebase project; its contents are
**not secret** — the Firebase config/API key is
[public by design](https://firebase.google.com/docs/projects/api-keys) and ships
inside the APK anyway, so a normal app can commit it (and often does, especially
in a private repo). It's **git-ignored here only** so that forks of this sample
use their own Firebase project instead of ours. When present, the
`com.google.gms.google-services` Gradle plugin is applied automatically and
initializes the default `FirebaseApp` for you.

### 2. Build-time Firebase config (fallback)

If you don't have a `google-services.json`, supply the three values directly and
they are baked into the APK's manifest at build time. Pass them as Gradle
properties:

```shell
./gradlew :google-adk-kotlin-examples-android:installDebug \
    -PFIREBASE_API_KEY=your_api_key \
    -PFIREBASE_APP_ID=your_app_id \
    -PFIREBASE_PROJECT_ID=your_project_id
```

or export the matching `FIREBASE_API_KEY` / `FIREBASE_APP_ID` /
`FIREBASE_PROJECT_ID` environment variables before building. (These are read at
build time, not from the device's environment.)

If neither method provides a configuration, the Gradle build prints a warning,
and the Firebase-backed examples (Skills, Firebase AI) start but show a message
explaining what to add instead of calling Firebase with a blank config. The
on-device examples are unaffected.

To change the model, edit `MODEL_NAME` in
[`FirebaseChatAgent.kt`](src/main/kotlin/com/google/adk/kt/examples/android/firebase/FirebaseChatAgent.kt)
(Firebase AI example) or
[`WizardApprenticeAgent.kt`](src/main/kotlin/com/google/adk/kt/examples/android/skillsassetsource/WizardApprenticeAgent.kt)
(Skills example).

> Note: the `FIREBASE_*` values (like those in `google-services.json`) are
> project *identifiers*, not secrets — they're public by design and always end
> up inside the APK. What you must keep out of the app and the repo is a
> genuinely secret key, such as a Gemini Developer API key or an Admin SDK
> service-account key; this sample uses neither (it talks to the model through
> Firebase AI Logic).

[`LlmAgent`]: ../../core/src/main/kotlin/com/google/adk/kt/agents/LlmAgent.kt
