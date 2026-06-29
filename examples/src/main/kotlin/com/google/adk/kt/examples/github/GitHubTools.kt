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

package com.google.adk.kt.examples.github

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import org.kohsuke.github.GHFileNotFoundException
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder

/**
 * Reusable GitHub function tools backed by the `org.kohsuke:github-api` client. Each returns a
 * `Map` with a `status` of `"success"` or `"error"`. Reads `GITHUB_TOKEN` from the environment;
 * callers set [dryRun] to gate writes.
 *
 * Defense in depth against prompt injection: the agent reads untrusted GitHub content (diffs, file
 * contents, issue/PR titles) and could be steered into harmful writes. Independently of the prompt,
 * the write tools (a) only target [writeRepoOwner]/[writeRepoName] when set, (b) only modify
 * Markdown files under `docs/`, and (c) are capped per run.
 */
object GitHubTools {

  /** Lists releases for a repository (most recent first) with their tag_name and name. */
  @Tool(
    name = "list_releases",
    description =
      "Lists releases for a repository, most recent first, returning each release's tag_name and " +
        "name.",
  )
  fun listReleases(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
  ): Map<String, Any> = guarded {
    val repo = connect().getRepository("$repoOwner/$repoName")
    val releases =
      repo.listReleases().map { release ->
        mapOf("tag_name" to (release.tagName ?: ""), "name" to (release.name ?: ""))
      }
    mapOf("releases" to releases)
  }

  /** Lists files changed between two release tags, optionally filtered to a path prefix. */
  @Tool(
    name = "get_changed_files",
    description =
      "Lists files changed between two release tags (without patch content), optionally filtered " +
        "to a path prefix. Use this to decide which files to inspect in detail.",
  )
  fun getChangedFiles(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("The older tag (base) for the comparison.") startTag: String,
    @Param("The newer tag (head) for the comparison.") endTag: String,
    @Param("Only include files whose path starts with this prefix. May be empty.")
    pathFilter: String? = null,
  ): Map<String, Any> = guarded {
    val repo = connect().getRepository("$repoOwner/$repoName")
    val files =
      repo
        .getCompare(startTag, endTag)
        .files
        .filter { pathFilter.isNullOrEmpty() || it.fileName.startsWith(pathFilter) }
        .map { file ->
          mapOf(
            "relative_path" to file.fileName,
            "status" to file.status,
            "additions" to file.linesAdded,
            "deletions" to file.linesDeleted,
          )
        }
    mapOf(
      "total_files" to files.size,
      "files" to files,
      "compare_url" to "https://github.com/$repoOwner/$repoName/compare/$startTag...$endTag",
    )
  }

  /** Gets the patch/diff for a single file between two release tags. */
  @Tool(
    name = "get_file_diff",
    description = "Gets the patch/diff for a single file between two release tags.",
  )
  fun getFileDiff(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("The older tag (base) for the comparison.") startTag: String,
    @Param("The newer tag (head) for the comparison.") endTag: String,
    @Param("Relative path of the file to get the diff for.") filePath: String,
  ): Map<String, Any> = guarded {
    val repo = connect().getRepository("$repoOwner/$repoName")
    val file =
      repo.getCompare(startTag, endTag).files.firstOrNull { it.fileName == filePath }
        ?: error("File $filePath not found in the comparison.")
    mapOf(
      "file" to
        mapOf(
          "relative_path" to file.fileName,
          "status" to file.status,
          "additions" to file.linesAdded,
          "deletions" to file.linesDeleted,
          "patch" to (file.patch ?: "No patch available."),
        )
    )
  }

  /** Searches a repository's content via the GitHub code search API. */
  @Tool(
    name = "search_code",
    description =
      "Searches a repository's content via the GitHub code search API and returns matching file " +
        "paths. Use it to find documentation related to a change, e.g. query " +
        "\"AgentBuilder path:docs\".",
  )
  fun searchCode(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("The code search query (GitHub search syntax).") query: String,
  ): Map<String, Any> = guarded {
    val matches =
      connect()
        .searchContent()
        .q(query)
        .repo("$repoOwner/$repoName")
        .list()
        .take(MAX_SEARCH_RESULTS)
        .map { content -> mapOf("file_path" to content.path) }
    mapOf("matches" to matches)
  }

  /** Reads and returns the raw content of a file. */
  @Tool(
    name = "get_file_content",
    description =
      "Reads and returns the raw content of a file in a repository. Pass this content back " +
        "(edited) to create_pull_request to apply changes.",
  )
  fun getFileContent(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("Relative path of the file to read.") filePath: String,
  ): Map<String, Any> = guarded {
    val file = connect().getRepository("$repoOwner/$repoName").getFileContent(filePath)
    if (file.isDirectory) error("$filePath is a directory, not a file.")
    val text = file.read().use { it.readBytes().decodeToString() }
    mapOf("file_path" to filePath, "content" to text)
  }

  /** Creates a new issue with the "docs updates" label. */
  @Tool(
    name = "create_issue",
    description =
      "Creates a new issue in the specified repository with the 'docs updates' label. Returns the " +
        "created issue's number and html_url.",
  )
  fun createIssue(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("The title of the issue.") title: String,
    @Param("The body of the issue.") body: String,
  ): Map<String, Any> {
    writeTargetError(repoOwner, repoName)?.let {
      return errorResponse(it)
    }
    if (dryRun) {
      return mapOf(
        STATUS_KEY to STATUS_DRY_RUN,
        "message" to "DRY RUN: no issue was created. Set DRY_RUN=0 to file issues for real.",
        "repository" to "$repoOwner/$repoName",
        "title" to title,
        "body" to body,
      )
    }
    if (issuesCreated >= MAX_ISSUES_PER_RUN) {
      return errorResponse("Issue creation limit reached ($MAX_ISSUES_PER_RUN per run).")
    }
    return guarded {
      val issue =
        connect()
          .getRepository("$repoOwner/$repoName")
          .createIssue(title)
          .body(body)
          .label(DOCS_UPDATES_LABEL)
          .create()
      issuesCreated++
      mapOf(
        "issue" to
          mapOf(
            "number" to issue.number,
            "html_url" to issue.htmlUrl.toString(),
            "title" to issue.title,
          )
      )
    }
  }

  /** Lists open issues carrying the "docs updates" label, to avoid filing duplicates. */
  @Tool(
    name = "find_doc_issues",
    description =
      "Lists OPEN issues in a repository that carry the 'docs updates' label, restricted to a " +
        "single code repository's release issues. Call this before creating an issue to avoid " +
        "filing a duplicate for the same release range.",
  )
  fun findDocIssues(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param(
      "Only return issues whose title mentions this code repository (e.g. \"adk-kotlin\"), so " +
        "results stay scoped to one language. Pass an empty string for no filter."
    )
    codeRepo: String,
  ): Map<String, Any> = guarded {
    val filter = codeRepo.trim().lowercase()
    val issues =
      connect()
        .getRepository("$repoOwner/$repoName")
        .getIssues(GHIssueState.OPEN)
        .filter { !it.isPullRequest && it.labels.any { label -> label.name == DOCS_UPDATES_LABEL } }
        .filter { it.title.lowercase().contains(filter) }
        .map {
          mapOf("number" to it.number, "title" to it.title, "html_url" to it.htmlUrl.toString())
        }
    mapOf("issues" to issues)
  }

  /** Lists open PRs referencing an issue number, to check whether it already has PRs. */
  @Tool(
    name = "find_pull_requests_for_issue",
    description =
      "Lists OPEN pull requests whose body references the given issue number. Use this to check " +
        "whether an issue already has pull requests before opening new ones (dedupe).",
  )
  fun findPullRequestsForIssue(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("The issue number to look for.") issueNumber: Int,
  ): Map<String, Any> = guarded {
    val marker = "#$issueNumber"
    val prs =
      connect()
        .getRepository("$repoOwner/$repoName")
        .getPullRequests(GHIssueState.OPEN)
        .filter { it.body?.contains(marker) == true }
        .map {
          mapOf("number" to it.number, "title" to it.title, "html_url" to it.htmlUrl.toString())
        }
    mapOf("pull_requests" to prs)
  }

  /** Opens ONE pull request for a recommendation, updating one or more documentation files. */
  @Tool(
    name = "create_pull_request",
    description =
      "Opens ONE pull request for a recommendation, updating one or more documentation files: " +
        "creates a branch off base_branch, commits each file, and opens the PR.",
  )
  fun createPullRequest(
    @Param("The repository owner.") repoOwner: String,
    @Param("The repository name.") repoName: String,
    @Param("Branch to merge into, e.g. \"main\".") baseBranch: String,
    @Param("Documentation files to update.") filePaths: List<String>,
    @Param("Full new content for each file, aligned 1:1 with file_paths.")
    newContents: List<String>,
    @Param("The pull request title.") title: String,
    @Param("The pull request body.") body: String,
  ): Map<String, Any> {
    if (filePaths.isEmpty() || filePaths.size != newContents.size) {
      return errorResponse("file_paths and new_contents must be non-empty and the same length.")
    }
    writeTargetError(repoOwner, repoName)?.let {
      return errorResponse(it)
    }
    for (path in filePaths) {
      docPathError(path)?.let {
        return errorResponse(it)
      }
    }
    if (dryRun) {
      return mapOf(
        STATUS_KEY to STATUS_DRY_RUN,
        "message" to "DRY RUN: no pull request was created. Set DRY_RUN=0 to open PRs for real.",
        "base_branch" to baseBranch,
        "file_paths" to filePaths,
        "title" to title,
        "body" to body,
      )
    }
    if (pullRequestsCreated >= MAX_PULL_REQUESTS_PER_RUN) {
      return errorResponse(
        "Pull request creation limit reached ($MAX_PULL_REQUESTS_PER_RUN per run)."
      )
    }
    return guarded {
      val repo = connect().getRepository("$repoOwner/$repoName")
      val baseSha = repo.getRef("heads/$baseBranch").getObject().sha
      val branch = "adk-docs-update-${System.currentTimeMillis()}"
      repo.createRef("refs/heads/$branch", baseSha)
      filePaths.forEachIndexed { i, filePath ->
        val change =
          repo.createContent().path(filePath).content(newContents[i]).branch(branch).message(title)
        try {
          change.sha(repo.getFileContent(filePath, branch).sha)
        } catch (e: GHFileNotFoundException) {
          // File does not exist yet; create it without a base sha.
        }
        change.commit()
      }
      val pr = repo.createPullRequest(title, branch, baseBranch, body)
      pullRequestsCreated++
      mapOf(
        "pull_request" to
          mapOf("number" to pr.number, "html_url" to pr.htmlUrl.toString(), "branch" to branch)
      )
    }
  }

  /** Connects to GitHub using GITHUB_TOKEN from the environment (anonymous if unset). */
  private fun connect(): GitHub {
    val builder = GitHubBuilder()
    val token = System.getenv("GITHUB_TOKEN")
    return if (token.isNullOrEmpty()) builder.build() else builder.withOAuthToken(token).build()
  }

  // Runs [block], tags the result as a success, and converts any failure into a structured error
  // result for the model. Catching broadly is intentional at the tool boundary: any failure should
  // surface to the LLM as data.
  private fun guarded(block: () -> Map<String, Any>): Map<String, Any> =
    try {
      block() + (STATUS_KEY to STATUS_SUCCESS)
    } catch (e: Exception) {
      errorResponse(e.message ?: e.toString())
    }

  private fun errorResponse(message: String): Map<String, Any> =
    mapOf(STATUS_KEY to STATUS_ERROR, "error_message" to message)

  // Returns an error message if writes are restricted (via writeRepoOwner/writeRepoName) and the
  // requested repository is not the allowed one, otherwise null. Prevents untrusted content from
  // redirecting writes to another repository.
  private fun writeTargetError(repoOwner: String, repoName: String): String? {
    val owner = writeRepoOwner
    val repo = writeRepoName
    return if (owner != null && repo != null && (owner != repoOwner || repo != repoName)) {
      "Refusing to write to $repoOwner/$repoName: writes are restricted to $owner/$repo."
    } else {
      null
    }
  }

  // Returns an error message if [path] is not a safe documentation file to write, otherwise null.
  // Untrusted model output may try to write outside docs/ (e.g. workflows or source); only Markdown
  // files under docs/ (excluding the auto-generated api-reference) are allowed.
  private fun docPathError(path: String): String? {
    if (path.isEmpty()) return "file path must not be empty."
    val normalized = path.replace('\\', '/')
    if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains(":")) {
      return "file path '$path' must be a relative path inside the repository."
    }
    if (!normalized.startsWith(DOCS_PATH_PREFIX)) {
      return "file path '$path' must be under '$DOCS_PATH_PREFIX'."
    }
    if (normalized.startsWith(API_REFERENCE_PREFIX)) {
      return "file path '$path' is auto-generated api-reference and must not be edited."
    }
    val lower = normalized.lowercase()
    if (!lower.endsWith(".md") && !lower.endsWith(".mdx")) {
      return "file path '$path' must be a Markdown (.md/.mdx) documentation file."
    }
    return null
  }

  /** When true, create_issue/create_pull_request return a preview instead of writing. */
  @JvmField var dryRun = true

  /**
   * When both are set, create_issue/create_pull_request refuse to write to any other repository,
   * regardless of the owner/repo the model passes. Set by the entry point to the docs repository so
   * untrusted content cannot redirect writes elsewhere.
   */
  @JvmField var writeRepoOwner: String? = null

  @JvmField var writeRepoName: String? = null

  private const val MAX_SEARCH_RESULTS = 50
  private const val DOCS_UPDATES_LABEL = "docs updates"
  private const val STATUS_KEY = "status"
  private const val STATUS_SUCCESS = "success"
  private const val STATUS_ERROR = "error"
  private const val STATUS_DRY_RUN = "dry_run"

  /** Only Markdown files under `docs/` (excluding api-reference) may be written by a PR. */
  private const val DOCS_PATH_PREFIX = "docs/"
  private const val API_REFERENCE_PREFIX = "docs/api-reference/"

  /** Per-run write caps to bound spam/abuse if the agent is hijacked. */
  private const val MAX_ISSUES_PER_RUN = 1
  private const val MAX_PULL_REQUESTS_PER_RUN = 20
  private var issuesCreated = 0
  private var pullRequestsCreated = 0
}
