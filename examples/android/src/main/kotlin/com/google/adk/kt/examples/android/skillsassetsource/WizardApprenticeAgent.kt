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

package com.google.adk.kt.examples.android.skillsassetsource

import android.content.Context
import com.google.adk.firebase.models.Firebase
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.skills.AssetSkillSource
import com.google.adk.kt.tools.SkillToolset
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI

/**
 * Builds the "wizard's apprentice" [LlmAgent] used by [SkillsAssetSourceActivity].
 *
 * The agent's [SkillToolset] is backed by an [AssetSkillSource] that reads spell definitions and
 * resources from the APK's `assets/skills/...` tree. The model is the cloud Firebase AI (Gemini)
 * backend from the `:google-adk-kotlin-firebase` module; its reliable function calling drives the
 * toolset's `list_skills` / `load_skill` / `load_skill_resource` tools. This needs a Firebase
 * configuration and network access (see the app README.md).
 */
internal object WizardApprenticeAgent {
  const val NAME: String = "wizard_apprentice"

  /**
   * The Firebase AI model to use. Any model available to your Firebase project works; override it
   * if this one is not enabled for you.
   */
  private const val MODEL_NAME: String = "gemini-3.5-flash"

  /**
   * Builds the agent against the given (already initialized) [firebaseApp], reading skills from
   * [context]'s APK assets.
   */
  fun create(context: Context, firebaseApp: FirebaseApp): LlmAgent =
    LlmAgent(
      name = NAME,
      model = Firebase.create(MODEL_NAME, FirebaseAI.getInstance(firebaseApp)),
      instruction =
        Instruction(
          """
          You are a young, somewhat nerdy wizard's apprentice. You have a grimoire of spells
          (skills) packaged into the app's APK assets. When the user asks for help:
            1. Call `list_skills` to discover which spells are available.
            2. Call `load_skill` on the most relevant one to read its SKILL.md instructions.
            3. Call `load_skill_resource` if the spell references additional asset files.
          Stay in-character: be eager, a touch clumsy, and speak like a fantasy novel character.
          """
            .trimIndent()
        ),
      toolsets =
        listOf(SkillToolset(AssetSkillSource.fromContext(context, skillsBaseDir = "skills"))),
    )
}
