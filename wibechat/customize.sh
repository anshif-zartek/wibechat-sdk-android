#!/usr/bin/env bash
# Runs inside the rebranded tree after the overlay is applied. Use it for
# WibeChat-specific edits that should survive every upstream sync.
set -euo pipefail

if ! grep -q '^org.gradle.tooling.parallel=' gradle.properties; then
  printf '\n# Enables parallel sync for Gradle 9.4+\norg.gradle.tooling.parallel=true\n' >> gradle.properties
fi
