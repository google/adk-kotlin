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
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.skills.AssetSkillSource
import com.google.adk.kt.tools.SkillToolset

/**
 * Builds the "wizard's apprentice" [LlmAgent] used by [SkillsAssetSourceActivity].
 *
 * The agent's [SkillToolset] is backed by an [AssetSkillSource] that reads spell definitions and
 * resources from the APK's `assets/skills/...` tree. The cloud Gemini model is required because the
 * on-device ML Kit Gemini Nano model does not support tool / function calls, which the
 * [SkillToolset] relies on. The API key is supplied at runtime by the activity (intent extra or
 * process env var) and is intentionally never baked into the build.
 */
internal object WizardApprenticeAgent {
  const val NAME: String = "wizard_apprentice"

  fun create(context: Context, apiKey: String): LlmAgent =
    LlmAgent(
      name = NAME,
      model = Gemini(name = "gemini-3.1-flash-lite", apiKey = apiKey),
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
