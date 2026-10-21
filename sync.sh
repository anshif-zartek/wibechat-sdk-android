#!/usr/bin/env bash
# Syncs upstream into main and regenerates this branch. See wibechat/README.md.
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/wibechat/sync.sh" "$@"
