#!/usr/bin/env python3
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Translates a phrase using the skill's bundled phrasebook.

Usage: translate.py <phrase> <language> [language...]

Run from the skill directory, so the phrasebook is read via a relative path.
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
  """Prints the translation of a phrase into each requested language.

  Args:
    argv: The phrase, followed by one or more target languages.

  Returns:
    The exit status: 0 on success, 1 if a translation is missing, 2 on bad
    usage, and 3 if the phrasebook is missing.
  """
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
