# ADK Docs Release Analyzer (Kotlin)

A single ADK agent that keeps documentation in sync with code releases. It
analyzes the differences between two releases of a code repository
(`google/adk-kotlin` by default), and—if the docs in a docs repository
(`google/adk-docs` by default) need updating—files a GitHub issue and opens a
pull request per recommendation that actually applies the edit.

This is a Kotlin port of the Python `adk_release_analyzer` + `adk_docs_updater`
samples, collapsed into a single `LlmAgent` for clarity.

## How it works

The agent (`AdkDocsReleaseAnalyzerAgent`) is equipped with KSP-generated
function tools (`GitHubTools`) that talk to GitHub through the
[`org.kohsuke:github-api`](https://github-api.kohsuke.org/) client library (no
hand-rolled REST code, no local cloning):

1.  `list_releases` — find the two most recent release tags to compare.
2.  `find_doc_issues` — list open `docs updates` issues to avoid duplicates.
3.  `find_pull_requests_for_issue` — check whether an issue already has PRs.
4.  `get_changed_files` — list files changed between the two tags (compare API).
5.  `get_file_diff` — fetch the patch for an individual file.
6.  `search_code` — find related documentation via GitHub code search.
7.  `get_file_content` — read a documentation file (raw content).
8.  `create_issue` — file a single issue with the recommended doc updates.
9.  `create_pull_request` — open one PR per recommendation, updating one or more
    doc files.

Deduplication is anchored on the issue: if an open issue already covers the same
release range **and** already has pull requests, the agent stops. If the issue
exists but has no PRs, it reuses the issue and opens the PRs. If no
documentation changes are warranted, it creates nothing.

## Running locally

```bash
# From the repository root:
export GITHUB_TOKEN=...           # token with issues + pull-requests write on the docs repo
export GOOGLE_API_KEY=...         # Gemini API key

# Analyze the two most recent releases (dry-run by default: previews the issue
# and pull requests, creates nothing):
./gradlew :google-adk-kotlin-examples:runDocsReleaseAnalyzer

# Or analyze an explicit range:
./gradlew :google-adk-kotlin-examples:runDocsReleaseAnalyzer \
    --args="--start-tag v0.1.0 --end-tag v0.2.0"

# Actually file the issue and open the PRs:
./gradlew :google-adk-kotlin-examples:runDocsReleaseAnalyzer --args="--no-dry-run"
```

By default the agent runs in **dry-run** mode: it does everything except write
to GitHub, and instead reports the issue and pull requests it *would* create.
Pass `--no-dry-run` to create them for real. Run with `--help` to see all
options.

## Command-line options

| Option                       | Default | Description                      |
| ---------------------------- | ------- | -------------------------------- |
| `--start-tag <tag>`          | –       | Older release tag (base).        |
| `--end-tag <tag>`            | –       | Newer release tag (head).        |
| `--dry-run` / `--no-dry-run` | dry-run | Preview issue + PRs vs. actually |
:                              :         : create them.                     :

## Configuration

The rest of the configuration is read from environment variables:

| Variable                  | Required | Default             | Description     |
| ------------------------- | -------- | ------------------- | --------------- |
| `GITHUB_TOKEN`            | yes      | –                   | Token with      |
:                           :          :                     : issues +        :
:                           :          :                     : pull-requests + :
:                           :          :                     : contents write  :
:                           :          :                     : on the docs     :
:                           :          :                     : repository.     :
| `GOOGLE_API_KEY`          | yes      | –                   | API key for the |
:                           :          :                     : Gemini API.     :
| `DOC_OWNER`               | no       | `google`            | Owner of the    |
:                           :          :                     : docs            :
:                           :          :                     : repository.     :
| `CODE_OWNER`              | no       | `google`            | Owner of the    |
:                           :          :                     : code            :
:                           :          :                     : repository.     :
| `DOC_REPO`                | no       | `adk-docs`          | Docs repository |
:                           :          :                     : name.           :
| `CODE_REPO`               | no       | `adk-kotlin`        | Code repository |
:                           :          :                     : name.           :
| `CODE_SOURCE_PATH_FILTER` | no       | `core/src/`         | Only analyze    |
:                           :          :                     : changes under   :
:                           :          :                     : this path.      :
| `MODEL`                   | no       | `gemini-pro-latest` | Model to use (a |
:                           :          :                     : Pro model helps :
:                           :          :                     : with deeper     :
:                           :          :                     : code            :
:                           :          :                     : understanding). :

## Automated mode (GitHub workflow)

The workflow at `.github/workflows/analyze-releases-for-adk-docs-updates.yml`
runs the agent automatically whenever a release is published (and supports
manual dispatch with optional `start_tag` / `end_tag`). It defaults to
**dry-run** (preview only, no writes); to actually create the issue and PRs,
trigger it manually (`workflow_dispatch`) with `dry_run` set to `false`.
