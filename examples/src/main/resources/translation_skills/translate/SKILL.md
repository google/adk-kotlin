---
name: translate
description: Translates a common phrase into one or more languages.
allowed-tools: []
---

Translate a phrase using the bundled phrasebook, rather than from memory.

Run `scripts/translate.py` with the `run_skill_script` tool. The script takes
the phrase as its first argument, followed by one argument per target language,
and prints one `language: translation` line per language.

Example usage:

Use the `run_skill_script` tool:

-   `skill_name`: "translate"
-   `file_path`: "scripts/translate.py"
-   `args`: ["good morning", "french", "japanese"]

That prints:

```
french: bonjour
japanese: ohayō
```

The phrasebook covers a small set of phrases (hello, goodbye, thank you, good
morning, please, yes, no) in Spanish, French, German, Polish, and Japanese. The
script exits with a non-zero status and writes to stderr when a phrase or
language is missing; report that to the user instead of guessing a translation.
