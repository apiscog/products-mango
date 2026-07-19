#!/bin/sh

set -euo pipefail

SCRIPT=/scripts/products-load-test.js
SETUP_LOG=/tmp/setup.log
TOTAL_START="$(date +%s)"

trap 'rm -f "$SETUP_LOG"' EXIT

run_phase() {
  mode="$1"
  product_id="$2"
  start="$(date +%s)"

  echo "============================================================"
  echo "Starting phase: $mode"
  echo "============================================================"

  k6 run \
    -e MODE="$mode" \
    -e SETUP_PRODUCT_ID="$product_id" \
    "$SCRIPT"

  end="$(date +%s)"
  echo "PHASE_WALL_TIME phase=$mode seconds=$((end - start))"
}

echo "Running functional setup"
if ! k6 run -e MODE=setup "$SCRIPT" > "$SETUP_LOG" 2>&1; then
  cat "$SETUP_LOG"
  exit 1
fi
cat "$SETUP_LOG"

PRODUCT_ID="$(grep -o 'SETUP_PRODUCT_ID=[0-9][0-9]*' "$SETUP_LOG" | tail -n 1 | cut -d= -f2)"
if [ -z "$PRODUCT_ID" ]; then
  echo "Could not extract SETUP_PRODUCT_ID from k6 setup output" >&2
  exit 1
fi

echo "Functional setup completed with product ID $PRODUCT_ID"
run_phase product-creation "$PRODUCT_ID"
run_phase price-query "$PRODUCT_ID"
run_phase history-query "$PRODUCT_ID"

TOTAL_END="$(date +%s)"
echo "============================================================"
echo "BENCHMARK_TOTALS product-creation=1000 price-query=20000 history-query=15000"
echo "BENCHMARK_WALL_TIME seconds=$((TOTAL_END - TOTAL_START))"
echo "Benchmark completed successfully"
