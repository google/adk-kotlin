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
    with streaming and thinking toggles.
-   **LiteRT-LM chat** — a multi-turn chat with an on-device agent run by
    LiteRT-LM from the [`:google-adk-kotlin-litertlm`](../../litertlm) module,
    with a streaming toggle and tool calling (via
    [`DeviceTools.kt`](src/main/kotlin/com/google/adk/kt/examples/android/litertlmchat/DeviceTools.kt)).
    Downloads its model on first use — see
    [The LiteRT-LM model](#the-litert-lm-model-for-the-litert-lm-chat-example)
    below.
-   **Firebase AI** — a chat backed by the cloud Firebase AI (Gemini) model from
    the [`:google-adk-kotlin-firebase`](../../firebase) module, with a streaming
    toggle and tool calling (via
    [`WeatherTools.kt`](src/main/kotlin/com/google/adk/kt/examples/android/firebase/WeatherTools.kt)).

**Room session**, **ML Kit chat** and **LiteRT-LM chat** infer fully on-device
and need no API key: the first two through ML Kit's Gemini Nano, the third
through a model file in the app's own storage. Each uses the network only to
obtain that model the first time: Gemini Nano may be downloaded on the first
run, and the LiteRT-LM example fetches its weights. The **Skills
(AssetSkillSource)** and **Firebase AI** examples call the cloud Firebase
backend on every turn, so they need a Firebase configuration and network access
— see [Configure Firebase](#configure-firebase-for-the-firebase-backed-examples)
below.

## Build & run

With a device or emulator connected:

```shell
./gradlew :google-adk-kotlin-examples-android:installDebug
```

Then launch **"ADK Android Examples"** from the launcher and pick an example.

## The LiteRT-LM model (for the LiteRT-LM chat example)

LiteRT-LM is handed a path to a `.litertlm` file and runs whatever model is in
it; it does not fetch models itself, so getting one onto the device is the app's
job. The other examples don't need any of this.

**Nothing to do to try it:** open the example and press **Download**. It streams
`gemma-4-E2B-it.litertlm` (about 2.5 GB) from the
[LiteRT community on Hugging Face](https://huggingface.co/litert-community) into
the app's own files directory, then loads it. Use Wi-Fi, and note the model
wants a device with 8 GB of RAM or more. After that it runs offline; loading the
weights takes a few seconds at each launch, and generation is slower than the
cloud examples — that's the model running on the device's CPU.

**To use a different model,** edit the `REPO` / `FILE_NAME` / `REVISION`
constants in
[`LiteRtLmModelStore.kt`](src/main/kotlin/com/google/adk/kt/examples/android/litertlmchat/LiteRtLmModelStore.kt).
Two things to watch: function calling only works with a model trained for tool
use, and repositories that require accepting a license need an access token this
sample does not implement. For those, and as a faster loop while developing,
push a file yourself — a `.litertlm` you supply takes precedence over the
downloaded one, so there is nothing to delete first:

```shell
adb push your-model.litertlm \
    /sdcard/Android/data/com.google.adk.kt.examples.android/files/
```

### How a real app would ship the model

Downloading on first use, as this sample does, is what the
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) does too,
and it is a reasonable choice — but it leaves you owning the hosting, the
storage, the retries and the updates. Bundling the file in the APK is not an
option at these sizes.

For an app shipped on Google Play, the delivery path to reach for is
[Play for On-device AI](https://developer.android.com/google/play/on-device-ai)
(currently in beta): the model becomes an **AI pack** that Play hosts, delivers
and updates alongside the app — at install time, fast-follow, or on demand —
through the
[`com.google.android.play:ai-delivery`](https://maven.google.com/web/index.html#com.google.android.play:ai-delivery)
library. Play then owns the download, the storage and the updates, and can send
a different variant of the model to different device tiers. The runtime side is
unchanged: you still hand `EngineConfig` a file path.

Watch the size limits, which is exactly why this sample does not use it: an
individual AI pack is capped at **1.5 GB compressed**, and 4 GB is the maximum
cumulative size of any version of your app. The 2.5 GB model above does not fit,
so taking the AI pack route means choosing a smaller model.

## Configure Firebase (for the Firebase-backed examples)

The **Skills (AssetSkillSource)** and **Firebase AI** examples need to know
which Firebase project to talk to; the on-device examples (Room session, ML Kit
chat, LiteRT-LM chat) don't need any of this. Two ways, in order of preference:

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
