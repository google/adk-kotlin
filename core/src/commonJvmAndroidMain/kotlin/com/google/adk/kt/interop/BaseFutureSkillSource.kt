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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.skills.Frontmatter
import com.google.adk.kt.skills.SkillSource
import com.google.adk.kt.skills.SkillSourceException
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await

/**
 * Java-friendly base for implementing a [SkillSource]: every engine method is a `suspend` returning
 * a [Result], and this base final-overrides each to await a [CompletableFuture] from the Java
 * subclass, mapping a completed future to [Result.success] and any failure to a [Result.failure] of
 * [SkillSourceException]. Return each future promptly and do any blocking work inside it. Complete
 * the future exceptionally with a [SkillSourceException] to report a skill error whose message
 * reaches the LLM.
 */
@AdkJavaInteropApi
abstract class BaseFutureSkillSource : SkillSource {

  final override suspend fun listFrontmatters(): Result<List<Frontmatter>> = awaitResult {
    listFrontmattersAsync()
  }

  final override suspend fun listResources(
    skillName: String,
    resourceDirectoryPath: String,
  ): Result<List<String>> = awaitResult { listResourcesAsync(skillName, resourceDirectoryPath) }

  final override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> = awaitResult {
    loadFrontmatterAsync(skillName)
  }

  final override suspend fun loadInstructions(skillName: String): Result<String> = awaitResult {
    loadInstructionsAsync(skillName)
  }

  final override suspend fun loadResource(
    skillName: String,
    resourcePath: String,
  ): Result<ByteArray> = awaitResult { loadResourceAsync(skillName, resourcePath) }

  protected abstract fun listFrontmattersAsync(): CompletableFuture<List<Frontmatter>>

  protected abstract fun listResourcesAsync(
    skillName: String,
    resourceDirectoryPath: String,
  ): CompletableFuture<List<String>>

  protected abstract fun loadFrontmatterAsync(skillName: String): CompletableFuture<Frontmatter>

  protected abstract fun loadInstructionsAsync(skillName: String): CompletableFuture<String>

  protected abstract fun loadResourceAsync(
    skillName: String,
    resourcePath: String,
  ): CompletableFuture<ByteArray>

  private suspend fun <T> awaitResult(future: () -> CompletableFuture<T>): Result<T> =
    try {
      Result.success(future().await())
    } catch (e: CancellationException) {
      throw e
    } catch (e: SkillSourceException) {
      Result.failure(e)
    } catch (e: Throwable) {
      Result.failure(SkillSourceException("The skill source failed to complete the request.", e))
    }
}
