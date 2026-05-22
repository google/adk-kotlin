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
"""A script to simulate casting a fireball spell.

This script takes a target as an argument and simulates casting a fireball,
including random damage calculation and different outcomes based on the damage.
"""

import argparse
import random
import time


def main() -> None:
  parser = argparse.ArgumentParser(description="Cast a fireball spell.")
  parser.add_argument(
      "--target",
      type=str,
      default="the darkness",
      help="The target of the fireball.",
  )
  args = parser.parse_args()

  # Output ritual flavor text
  print("🔥 Gathering magical energy...")
  print("🔥 Chanting arcane words...")

  # Calculate damage from a random range
  damage = random.randint(10, 50)
  print(
      f"💥 A massive fireball erupts from your hands, striking {args.target}"
      f" for {damage} fire damage!"
  )

  # Determine the outcome based on the damage inflicted
  if damage >= 40:
    print(f"✨ CRITICAL HIT! {args.target} is incinerated!")
  elif damage <= 15:
    print(f"💨 It's a glancing blow. {args.target} is merely singed.")


if __name__ == "__main__":
  main()
