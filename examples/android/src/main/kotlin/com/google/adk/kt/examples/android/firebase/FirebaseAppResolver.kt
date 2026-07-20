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

package com.google.adk.kt.examples.android.firebase

import android.content.Context
import android.content.pm.PackageManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Resolves the [FirebaseApp] this sample talks to. This is Firebase setup plumbing, kept out of
 * [FirebaseChatActivity] so the activity shows only how ADK is used (build an agent, run it).
 *
 * Resolution order:
 * 1. Standard setup: a `google-services.json` in the app module's root, processed by the
 *    `com.google.gms.google-services` Gradle plugin, auto-initializes the default [FirebaseApp] at
 *    process start. In a normal Firebase app this is all you need — [resolve] just returns it and
 *    the rest of this class is irrelevant.
 * 2. Fallback: the `FIREBASE_API_KEY` / `FIREBASE_APP_ID` / `FIREBASE_PROJECT_ID` values baked into
 *    the APK manifest at build time (see `build.gradle.kts` / `AndroidManifest.xml`), used to build
 *    a named [FirebaseApp] on demand.
 *
 * Returns null when neither is available; see README.md for setup.
 */
internal object FirebaseAppResolver {

  /** Prefix of the manifest metadata keys holding the build-time fallback config. */
  private const val META_DATA_PREFIX = "com.google.adk."

  /** Non-default FirebaseApp name used for the build-time-config fallback path. */
  private const val FALLBACK_APP_NAME = "adk-firebase-example"

  /**
   * Resolves a usable [FirebaseApp], or null if neither the standard nor fallback config exists.
   */
  fun resolve(context: Context): FirebaseApp? {
    // 1) Standard path: google-services.json + the google-services plugin auto-initialize the
    //    default FirebaseApp at process start (via Firebase's init ContentProvider).
    runCatching { FirebaseApp.getInstance() }
      .getOrNull()
      ?.let {
        return it
      }

    // 2) Fallback path: reuse the named app if an earlier call already built it, otherwise assemble
    //    one from the FIREBASE_* manifest meta-data baked in at build time.
    runCatching { FirebaseApp.getInstance(FALLBACK_APP_NAME) }
      .getOrNull()
      ?.let {
        return it
      }
    val apiKey = bakedMetaData(context, "FIREBASE_API_KEY") ?: return null
    val appId = bakedMetaData(context, "FIREBASE_APP_ID") ?: return null
    val projectId = bakedMetaData(context, "FIREBASE_PROJECT_ID") ?: return null

    return FirebaseApp.initializeApp(
      context.applicationContext,
      FirebaseOptions.Builder()
        .setApiKey(apiKey)
        .setApplicationId(appId)
        .setProjectId(projectId)
        .build(),
      FALLBACK_APP_NAME,
    )
  }

  /**
   * Reads the `${[META_DATA_PREFIX]}${[token]}` application manifest metadata entry. Returns null
   * if it is missing, blank, or still holds its unresolved `${[token]}` build placeholder (i.e. no
   * value was supplied at build time).
   */
  private fun bakedMetaData(context: Context, token: String): String? {
    val raw =
      try {
        val appInfo =
          context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
          )
        appInfo.metaData?.getString(META_DATA_PREFIX + token)
      } catch (_: PackageManager.NameNotFoundException) {
        null
      }
    return raw?.takeIf { it.isNotBlank() && !it.contains(token) }
  }
}
