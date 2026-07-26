#!/usr/bin/env bash
# Backwards-compatible arm64-v8a entry point.
#
# The multi-ABI driver is the single source of truth for SDK setup, verified
# downloads, active-CMake-output selection, complete-set staging, and ELF LOAD
# alignment validation. Keeping a second copy of that logic previously allowed
# this script to select stale native outputs and omit libc++_shared.so.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec bash "$PROJECT_ROOT/scripts/build-native-multiarch-in-wsl.sh" arm64-v8a
