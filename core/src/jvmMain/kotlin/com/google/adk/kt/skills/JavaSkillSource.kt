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

package com.google.adk.kt.skills

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Java-friendly base for implementing [SkillSource]. A Java subclass overrides the plain, blocking
 * `*Blocking` methods and throws [SkillSourceException] on failure; this base runs each on
 * [context] and wraps the outcome into the `suspend` + [Result] contract that Java cannot express
 * (`Result` is a value class whose factories are `inline`). Leave [context] as the default to run
 * on the caller's dispatcher, or inject one to move blocking work off it.
 */
abstract class JavaSkillSource(private val context: CoroutineContext = EmptyCoroutineContext) :
  SkillSource {

  /** @see SkillSource.listFrontmatters */
  @Throws(SkillSourceException::class)
  protected abstract fun listFrontmattersBlocking(): List<Frontmatter>

  /** @see SkillSource.listResources */
  @Throws(SkillSourceException::class)
  protected abstract fun listResourcesBlocking(
    skillName: String,
    resourceDirectoryPath: String,
  ): List<String>

  /** @see SkillSource.loadFrontmatter */
  @Throws(SkillSourceException::class)
  protected abstract fun loadFrontmatterBlocking(skillName: String): Frontmatter

  /** @see SkillSource.loadInstructions */
  @Throws(SkillSourceException::class)
  protected abstract fun loadInstructionsBlocking(skillName: String): String

  /** @see SkillSource.loadResource */
  @Throws(SkillSourceException::class)
  protected abstract fun loadResourceBlocking(skillName: String, resourcePath: String): ByteArray

  final override suspend fun listFrontmatters(): Result<List<Frontmatter>> = wrap {
    listFrontmattersBlocking()
  }

  final override suspend fun listResources(
    skillName: String,
    resourceDirectoryPath: String,
  ): Result<List<String>> = wrap { listResourcesBlocking(skillName, resourceDirectoryPath) }

  final override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> = wrap {
    loadFrontmatterBlocking(skillName)
  }

  final override suspend fun loadInstructions(skillName: String): Result<String> = wrap {
    loadInstructionsBlocking(skillName)
  }

  final override suspend fun loadResource(
    skillName: String,
    resourcePath: String,
  ): Result<ByteArray> = wrap { loadResourceBlocking(skillName, resourcePath) }

  private suspend fun <T> wrap(block: () -> T): Result<T> =
    withContext(context) {
      try {
        Result.success(block())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        Result.failure(e)
      }
    }
}
