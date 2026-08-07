#!/usr/bin/env python3
"""Translates a phrase using the skill's bundled phrasebook.

Usage: translate.py <phrase> <language> [language...]

Run from the skill directory, so the phrasebook is read via a relative path.
The interpreter comes from the shebang above rather than from the toolset, so a
skill can ship scripts in whatever language suits it.
"""

import csv
import pathlib
import sys

PHRASEBOOK = pathlib.Path("references/phrasebook.tsv")


def load_phrasebook():
  """Returns {(phrase, language): translation} read from the bundled TSV."""
  with PHRASEBOOK.open(encoding="utf-8") as tsv:
    return {
        (row["phrase"], row["language"]): row["translation"]
        for row in csv.DictReader(tsv, delimiter="\t")
    }


def main(argv):
  if len(argv) < 2:
    print(
        "usage: translate.py <phrase> <language> [language...]", file=sys.stderr
    )
    return 2

  if not PHRASEBOOK.is_file():
    print(f"phrasebook not found at {PHRASEBOOK}", file=sys.stderr)
    return 3

  phrasebook = load_phrasebook()
  # Lower-case lookups so the phrase and languages are case-insensitive.
  phrase, languages = argv[0].lower(), argv[1:]

  status = 0
  for language in languages:
    translation = phrasebook.get((phrase, language.lower()))
    if translation is None:
      print(f"{language}: no translation for '{phrase}'", file=sys.stderr)
      status = 1
      continue
    print(f"{language}: {translation}")
  return status


if __name__ == "__main__":
  sys.exit(main(sys.argv[1:]))
