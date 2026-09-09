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

import com.google.adk.kt.skills.Frontmatter
import com.google.adk.kt.skills.SkillSourceException
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BaseFutureSkillSourceTest {

  private val frontmatter = Frontmatter(name = "greet", description = "Greets the user.")

  @Test
  fun completedFutures_mapToSuccess() = runBlocking {
    val source =
      object : BaseFutureSkillSource() {
        override fun listFrontmattersAsync() =
          CompletableFuture.completedFuture(listOf(frontmatter))

        override fun listResourcesAsync(skillName: String, resourceDirectoryPath: String) =
          CompletableFuture.completedFuture(listOf("assets/greeting.txt"))

        override fun loadFrontmatterAsync(skillName: String) =
          CompletableFuture.completedFuture(frontmatter)

        override fun loadInstructionsAsync(skillName: String) =
          CompletableFuture.completedFuture("Say hello warmly.")

        override fun loadResourceAsync(skillName: String, resourcePath: String) =
          CompletableFuture.completedFuture("hi".encodeToByteArray())
      }

    assertThat(source.listFrontmatters().getOrThrow()).containsExactly(frontmatter)
    assertThat(source.listResources("greet", "assets").getOrThrow())
      .containsExactly("assets/greeting.txt")
    assertThat(source.loadFrontmatter("greet").getOrThrow()).isEqualTo(frontmatter)
    assertThat(source.loadInstructions("greet").getOrThrow()).isEqualTo("Say hello warmly.")
    assertThat(source.loadResource("greet", "assets/greeting.txt").getOrThrow())
      .isEqualTo("hi".encodeToByteArray())
  }

  @Test
  fun skillSourceException_isForwardedUnchanged() = runBlocking {
    val boom = SkillSourceException("Skill not found.")
    val result = failingSource(boom).loadFrontmatter("missing")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isSameInstanceAs(boom)
  }

  @Test
  fun otherException_isWrappedInSkillSourceException() = runBlocking {
    val result = failingSource(IllegalStateException("backing store offline")).loadFrontmatter("x")

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SkillSourceException::class.java)
    assertThat(error).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun synchronousThrow_isWrappedInSkillSourceException() = runBlocking {
    val source =
      object : BaseFutureSkillSource() {
        override fun listFrontmattersAsync() =
          CompletableFuture.completedFuture(emptyList<Frontmatter>())

        override fun listResourcesAsync(skillName: String, resourceDirectoryPath: String) =
          CompletableFuture.completedFuture(emptyList<String>())

        override fun loadFrontmatterAsync(skillName: String): CompletableFuture<Frontmatter> =
          throw IllegalStateException("thrown before returning a future")

        override fun loadInstructionsAsync(skillName: String) =
          CompletableFuture.completedFuture("")

        override fun loadResourceAsync(skillName: String, resourcePath: String) =
          CompletableFuture.completedFuture(ByteArray(0))
      }

    val error = source.loadFrontmatter("x").exceptionOrNull()

    assertThat(error).isInstanceOf(SkillSourceException::class.java)
    assertThat(error).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
  }

  /**
   * A source whose [loadFrontmatterAsync] fails with [error]; other methods return empty results.
   */
  private fun failingSource(error: Throwable): BaseFutureSkillSource =
    object : BaseFutureSkillSource() {
      override fun listFrontmattersAsync() =
        CompletableFuture.completedFuture(emptyList<Frontmatter>())

      override fun listResourcesAsync(skillName: String, resourceDirectoryPath: String) =
        CompletableFuture.completedFuture(emptyList<String>())

      override fun loadFrontmatterAsync(skillName: String): CompletableFuture<Frontmatter> =
        CompletableFuture<Frontmatter>().apply { completeExceptionally(error) }

      override fun loadInstructionsAsync(skillName: String) = CompletableFuture.completedFuture("")

      override fun loadResourceAsync(skillName: String, resourcePath: String) =
        CompletableFuture.completedFuture(ByteArray(0))
    }
}
