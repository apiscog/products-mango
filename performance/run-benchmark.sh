#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
BENCHMARK_SCRIPT="$SCRIPT_DIR/benchmark.sh"

if [[ ! -f "$BENCHMARK_SCRIPT" ]]; then
  echo "Official benchmark not found: $BENCHMARK_SCRIPT" >&2
  exit 1
fi

for dependency in bash curl grep cut tr date bc sleep; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    echo "Missing dependency required by the official benchmark: $dependency" >&2
    exit 1
  fi
done

echo "Running the immutable official benchmark: $BENCHMARK_SCRIPT"
echo "The output below is produced directly by performance/benchmark.sh."

cd "$REPOSITORY_ROOT"
exec bash "$BENCHMARK_SCRIPT"
