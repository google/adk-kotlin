#!/bin/bash
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

# Default summoning type is random
TYPE="random"

# Parse command-line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --type) TYPE="$2"; shift ;;
        *) echo "Unknown parameter passed: $1"; exit 1 ;;
    esac
    shift
done

# If no specific type was provided, pick a random creature
if [ "$TYPE" = "random" ]; then
    creatures=("an owl" "a black cat" "a toad" "a raven" "a miniature dragon" "a glowing wisp")
    TYPE=${creatures[$RANDOM % ${#creatures[@]}]}
else
    TYPE="a $TYPE"
fi

# Output descriptive flavor text for the spell casting
echo "✨ You draw a magical circle on the ground..."
echo "🌟 You sprinkle some mystic dust and chant a summoning incantation..."
echo "💨 A puff of magical smoke appears!"
echo "🐾 In the center of the circle, $TYPE appears, ready to assist you!"
