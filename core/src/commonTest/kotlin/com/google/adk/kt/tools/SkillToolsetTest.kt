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

package com.google.adk.kt.tools

import com.google.adk.kt.skills.Frontmatter
import com.google.adk.kt.skills.SkillSource
import com.google.adk.kt.skills.SkillSourceException
import com.google.adk.kt.testing.testToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Unit tests for [SkillToolset]. */
class SkillToolsetTest {

  private val mockFrontmatters =
    listOf(
      Frontmatter(name = "skill1", description = "Description 1"),
      Frontmatter(name = "skill2", description = "Description 2"),
    )
  private val mockInstructions = mapOf("skill1" to "Instructions 1", "skill2" to "Instructions 2")

  private val mockSource =
    object : SkillSource {
      override suspend fun listFrontmatters(): Result<List<Frontmatter>> =
        Result.success(mockFrontmatters)

      override suspend fun listResources(
        skillName: String,
        resourceDirectoryPath: String,
      ): Result<List<String>> = Result.success(emptyList())

      override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> {
        val frontmatter = mockFrontmatters.find { it.name == skillName }
        return if (frontmatter != null) {
          Result.success(frontmatter)
        } else {
          Result.failure(SkillSourceException("Skill $skillName not found"))
        }
      }

      override suspend fun loadInstructions(skillName: String): Result<String> {
        val instructions = mockInstructions[skillName]
        return if (instructions != null) {
          Result.success(instructions)
        } else {
          Result.failure(SkillSourceException("Skill $skillName not found"))
        }
      }

      override suspend fun loadResource(
        skillName: String,
        resourcePath: String,
      ): Result<ByteArray> {
        if (skillName != "skill1") {
          return Result.failure(SkillSourceException("Skill $skillName not found"))
        }
        if (SkillSource.VALID_RESOURCE_DIRS.none { resourcePath.startsWith("$it/") }) {
          return Result.failure(
            SkillSourceException(
              "Invalid resource path: $resourcePath must be within 'references/', 'assets/', or 'scripts/'"
            )
          )
        }
        return when (resourcePath) {
          "references/ref.md" -> Result.success("Ref 1 Content".encodeToByteArray())
          "assets/config.txt" -> Result.success("Config 1".encodeToByteArray())
          "assets/asset.dat" -> Result.success(byteArrayOf(0xFF.toByte()))
          "scripts/run.sh" -> Result.success("echo 'Run 1'".encodeToByteArray())
          else -> Result.failure(SkillSourceException("Resource $resourcePath not found"))
        }
      }
    }

  private val skillToolset = SkillToolset(mockSource)

  @Test
  fun getTools_returnsThreeTools() = runTest {
    val tools = skillToolset.getTools(null)
    assertEquals(3, tools.size)
    assertTrue(tools.any { it.name == SkillToolset.TOOL_NAME_LIST_SKILLS })
    assertTrue(tools.any { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL })
    assertTrue(tools.any { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE })
  }

  @Test
  fun listSkillsTool_run_returnsSkillFrontmatter() = runTest {
    val tool = skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LIST_SKILLS }
    val result = tool.run(testToolContext(), emptyMap()) as Map<*, *>
    val skillsList = assertIs<List<*>>(result["skills"])
    assertEquals(2, skillsList.size)
    val first = assertIs<Map<*, *>>(skillsList[0])
    assertEquals("skill1", first["name"])
    assertEquals("Description 1", first["description"])
    assertEquals("skill2", assertIs<Map<*, *>>(skillsList[1])["name"])
  }

  @Test
  fun loadSkillTool_run_returnsSkillData() = runTest {
    val tools = skillToolset.getTools(null)
    val loadSkillTool = tools.first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL }

    val result =
      loadSkillTool.run(testToolContext(), mapOf(SkillToolset.PARAM_SKILL_NAME to "skill1"))
        as Map<*, *>

    assertEquals("skill1", result[SkillToolset.PARAM_SKILL_NAME])
    assertEquals("Instructions 1", result[SkillToolset.KEY_INSTRUCTIONS])
    assertTrue(result[SkillToolset.KEY_FRONTMATTER] is Map<*, *>)
    val frontmatter = result[SkillToolset.KEY_FRONTMATTER] as Map<*, *>
    assertEquals("skill1", frontmatter["name"])
    assertEquals("Description 1", frontmatter["description"])
  }

  @Test
  fun loadSkillTool_run_requiresName() = runTest {
    val tools = skillToolset.getTools(null)
    val loadSkillTool = tools.first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL }

    val result = loadSkillTool.run(testToolContext(), emptyMap()) as Map<*, *>

    assertNotNull(result[SkillToolset.KEY_ERROR])
  }

  @Test
  fun loadSkillTool_run_skillNotFound_returnsLoadFailedWithDetailedMessage() = runTest {
    val tools = skillToolset.getTools(null)
    val loadSkillTool = tools.first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL }

    val result =
      loadSkillTool.run(testToolContext(), mapOf(SkillToolset.PARAM_SKILL_NAME to "unknown"))
        as Map<*, *>

    assertEquals("Skill unknown not found", result[SkillToolset.KEY_ERROR])
  }

  @Test
  fun loadSkillResourceTool_run_returnsReference() = runTest {
    val tool =
      skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE }
    val result =
      tool.run(
        testToolContext(),
        mapOf(
          SkillToolset.PARAM_SKILL_NAME to "skill1",
          SkillToolset.PARAM_PATH to "references/ref.md",
        ),
      ) as Map<*, *>
    assertEquals("Ref 1 Content", result[SkillToolset.KEY_CONTENT])
  }

  @Test
  fun loadSkillResourceTool_run_returnsTextAsset() = runTest {
    val tool =
      skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE }
    val result =
      tool.run(
        testToolContext(),
        mapOf(
          SkillToolset.PARAM_SKILL_NAME to "skill1",
          SkillToolset.PARAM_PATH to "assets/config.txt",
        ),
      ) as Map<*, *>
    assertEquals("Config 1", result[SkillToolset.KEY_CONTENT])
  }

  @Test
  fun loadSkillResourceTool_run_handlesBinaryAsset() = runTest {
    val tool =
      skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE }
    val result =
      tool.run(
        testToolContext(),
        mapOf(
          SkillToolset.PARAM_SKILL_NAME to "skill1",
          SkillToolset.PARAM_PATH to "assets/asset.dat",
        ),
      ) as Map<*, *>
    assertEquals(SkillToolset.MSG_BINARY_FILE, result[SkillToolset.KEY_STATUS])
  }

  @Test
  fun loadSkillResourceTool_run_returnsScriptSource() = runTest {
    val tool =
      skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE }
    val result =
      tool.run(
        testToolContext(),
        mapOf(
          SkillToolset.PARAM_SKILL_NAME to "skill1",
          SkillToolset.PARAM_PATH to "scripts/run.sh",
        ),
      ) as Map<*, *>
    assertEquals("echo 'Run 1'", result[SkillToolset.KEY_CONTENT])
  }

  @Test
  fun loadSkillResourceTool_run_resourceNotFound_returnsLoadFailedWithDetailedMessage() = runTest {
    val tool =
      skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE }
    val result =
      tool.run(
        testToolContext(),
        mapOf(
          SkillToolset.PARAM_SKILL_NAME to "skill1",
          SkillToolset.PARAM_PATH to "references/unknown.md",
        ),
      ) as Map<*, *>
    assertEquals("Resource references/unknown.md not found", result[SkillToolset.KEY_ERROR])
  }

  @Test
  fun loadSkillResourceTool_run_invalidPath_returnsLoadFailedWithDetailedMessage() = runTest {
    val tool =
      skillToolset.getTools(null).first { it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE }
    val result =
      tool.run(
        testToolContext(),
        mapOf(
          SkillToolset.PARAM_SKILL_NAME to "skill1",
          SkillToolset.PARAM_PATH to "other/file.txt",
        ),
      ) as Map<*, *>
    assertTrue(
      (result[SkillToolset.KEY_ERROR] as String).contains(
        "must be within 'references/', 'assets/', or 'scripts/'"
      )
    )
  }

  @Test
  fun listSkillsTool_run_unexpectedWrappedError_returnsGenericMessageWithoutLeakingDetails() =
    runTest {
      val sensitiveMessage = "Permission denied: /etc/secret/path"
      val brokenSource =
        object : SkillSource by mockSource {
          override suspend fun listFrontmatters(): Result<List<Frontmatter>> =
            Result.failure(RuntimeException(sensitiveMessage))
        }
      val tool =
        SkillToolset(brokenSource).getTools(null).first {
          it.name == SkillToolset.TOOL_NAME_LIST_SKILLS
        }

      val thrown = assertFailsWith<RuntimeException> { tool.run(testToolContext(), emptyMap()) }
      assertEquals(sensitiveMessage, thrown.message)
    }

  @Test
  fun listSkillsTool_run_unexpectedThrownError_propagates() = runTest {
    val brokenSource =
      object : SkillSource by mockSource {
        override suspend fun listFrontmatters(): Result<List<Frontmatter>> =
          throw RuntimeException("boom")
      }
    val tool =
      SkillToolset(brokenSource).getTools(null).first {
        it.name == SkillToolset.TOOL_NAME_LIST_SKILLS
      }

    val thrown = assertFailsWith<RuntimeException> { tool.run(testToolContext(), emptyMap()) }
    assertEquals("boom", thrown.message)
  }

  @Test
  fun loadSkillTool_run_unexpectedWrappedError_propagates() = runTest {
    val sensitiveMessage = "I/O failure on /var/data/internal"
    val brokenSource =
      object : SkillSource by mockSource {
        override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> =
          Result.failure(RuntimeException(sensitiveMessage))
      }
    val tool =
      SkillToolset(brokenSource).getTools(null).first {
        it.name == SkillToolset.TOOL_NAME_LOAD_SKILL
      }

    val thrown =
      assertFailsWith<RuntimeException> {
        tool.run(testToolContext(), mapOf(SkillToolset.PARAM_SKILL_NAME to "skill1"))
      }

    assertEquals(sensitiveMessage, thrown.message)
  }

  @Test
  fun loadSkillTool_run_loadInstructionsFails_skillNotFound_returnsLoadFailedWithDetailedMessage() =
    runTest {
      // Simulates a case where the skill becomes invalid (e.g., disappears) between
      // `loadFrontmatter` call and `loadInstructions` call.
      val brokenSource =
        object : SkillSource by mockSource {
          override suspend fun loadInstructions(skillName: String): Result<String> =
            Result.failure(SkillSourceException("Skill $skillName not found"))
        }
      val tool =
        SkillToolset(brokenSource).getTools(null).first {
          it.name == SkillToolset.TOOL_NAME_LOAD_SKILL
        }

      val result =
        tool.run(testToolContext(), mapOf(SkillToolset.PARAM_SKILL_NAME to "skill1")) as Map<*, *>

      assertEquals("Skill skill1 not found", result[SkillToolset.KEY_ERROR])
    }

  @Test
  fun loadSkillTool_run_loadInstructionsFails_unexpectedWrappedError_propagates() = runTest {
    val sensitiveMessage = "Permission denied: /var/secret/instructions.md"
    val brokenSource =
      object : SkillSource by mockSource {
        override suspend fun loadInstructions(skillName: String): Result<String> =
          Result.failure(RuntimeException(sensitiveMessage))
      }
    val tool =
      SkillToolset(brokenSource).getTools(null).first {
        it.name == SkillToolset.TOOL_NAME_LOAD_SKILL
      }

    val thrown =
      assertFailsWith<RuntimeException> {
        tool.run(testToolContext(), mapOf(SkillToolset.PARAM_SKILL_NAME to "skill1"))
      }

    assertEquals(sensitiveMessage, thrown.message)
  }

  @Test
  fun loadSkillResourceTool_run_unexpectedWrappedError_propagates() = runTest {
    val sensitiveMessage = "Stack trace at internal.module.Foo.bar(Foo.kt:42)"
    val brokenSource =
      object : SkillSource by mockSource {
        override suspend fun loadResource(
          skillName: String,
          resourcePath: String,
        ): Result<ByteArray> = Result.failure(RuntimeException(sensitiveMessage))
      }
    val tool =
      SkillToolset(brokenSource).getTools(null).first {
        it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE
      }

    val thrown =
      assertFailsWith<RuntimeException> {
        tool.run(
          testToolContext(),
          mapOf(
            SkillToolset.PARAM_SKILL_NAME to "skill1",
            SkillToolset.PARAM_PATH to "references/ref.md",
          ),
        )
      }

    assertEquals(sensitiveMessage, thrown.message)
  }

  @Test
  fun loadSkillResourceTool_run_unexpectedThrownError_propagates() = runTest {
    val brokenSource =
      object : SkillSource by mockSource {
        override suspend fun loadResource(
          skillName: String,
          resourcePath: String,
        ): Result<ByteArray> = throw RuntimeException("boom")
      }
    val tool =
      SkillToolset(brokenSource).getTools(null).first {
        it.name == SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE
      }

    val thrown =
      assertFailsWith<RuntimeException> {
        tool.run(
          testToolContext(),
          mapOf(
            SkillToolset.PARAM_SKILL_NAME to "skill1",
            SkillToolset.PARAM_PATH to "references/ref.md",
          ),
        )
      }
    assertEquals("boom", thrown.message)
  }

  @Test
  fun getSkillCatalogInstruction_returnsExpectedString() = runTest {
    val instruction = skillToolset.getSkillCatalogInstruction()
    assertNotNull(instruction)
    assertTrue(instruction.contains("load_skill"))
    assertTrue(instruction.contains("load_skill_resource"))
    assertTrue(instruction.contains("<skill name=\"skill1\">"))
  }

  @Test
  fun getSkillCatalogInstruction_noSkills_returnsNull() = runTest {
    val emptySource =
      object : SkillSource by mockSource {
        override suspend fun listFrontmatters(): Result<List<Frontmatter>> =
          Result.success(emptyList())
      }
    val emptyToolset = SkillToolset(emptySource)

    val instruction = emptyToolset.getSkillCatalogInstruction()

    kotlin.test.assertNull(instruction)
  }

  @Test
  fun getSkillCatalogInstruction_listFrontmattersFails_returnsNull() = runTest {
    val brokenSource =
      object : SkillSource by mockSource {
        override suspend fun listFrontmatters(): Result<List<Frontmatter>> =
          Result.failure(SkillSourceException("Skills base directory does not exist"))
      }

    val instruction = SkillToolset(brokenSource).getSkillCatalogInstruction()

    kotlin.test.assertNull(instruction)
  }
}
