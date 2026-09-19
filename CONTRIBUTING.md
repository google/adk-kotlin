# How to contribute

We'd love to accept your patches and contributions to this project.

## Before you begin

### Sign our Contributor License Agreement

Contributions to this project must be accompanied by a
[Contributor License Agreement](https://cla.developers.google.com/about) (CLA).
You (or your employer) retain the copyright to your contribution; this simply
gives us permission to use and redistribute your contributions as part of the
project.

If you or your current employer have already signed the Google CLA (even if it
was for a different project), you probably don't need to do it again.

Visit <https://cla.developers.google.com/> to see your current agreements or to
sign a new one.

### Review our community guidelines

This project follows
[Google's Open Source Community Guidelines](https://opensource.google/conduct/).

## Contribution process

### Code reviews

All submissions, including submissions by project members, require review. We
use GitHub pull requests for this purpose. Consult
[GitHub Help](https://help.github.com/articles/about-pull-requests/) for more
information on using pull requests.

## PR policy

### Format

Code must be formatted according to the
[Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide).

You must commit formatted code, otherwise the changelist will fail to build and
cannot be submitted.

### Single Commit

Pull Requests must contain only a **single commit.**

This is due to how Google replicates this Git repository both into and from its
internal _monorepo_ (see [Wikipedia](https://en.wikipedia.org/wiki/Monorepo) and
[Paper](https://research.google/pubs/why-google-stores-billions-of-lines-of-code-in-a-single-repository/))
with [🦛 Copybara](https://github.com/google/copybara).

When adjusting a PR to code review feedback, please use `git commit --amend`.

You can use `git rebase -i main` to _meld/squash_ existing commits into one.

Then use `git push --force-with-lease` to update the branch of your PR.

We cannot merge your PR until you fix this.

### AI Generated code

It's ok to generate the first draft using AI but we would like code which has
gone through human refinement.

### Alignment with [adk-python](https://github.com/google/adk-python)

We lean on adk-python for being the source of truth and one should refer to
adk-python for validation.

### KDocs

We want our KDocs to be concise and meaningful.

## Agent skills

Instructions for coding agents live in [AGENTS.md](AGENTS.md), which points at
the skills under `.agents/skills/`. Keep them in step with the code: a change
to the runner, the KSP processor, or the build setup usually needs a matching
edit to the skill that documents it.

How each tool loads them:

-   **Gemini CLI** reads `AGENTS.md` via `.gemini/settings.json`.
-   **Anything else** should be pointed at `AGENTS.md` and `.agents/skills/`
    directly rather than given a duplicate copy of the content.
