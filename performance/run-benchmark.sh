#!/bin/sh

set -eu

SCRIPT=/scripts/products-load-test.js
TOTAL_START="$(date +%s)"

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
if ! k6 run -e MODE=setup "$SCRIPT" > /tmp/setup.log 2>&1; then
  cat /tmp/setup.log
  exit 1
fi
cat /tmp/setup.log

PRODUCT_ID="$(grep -o 'SETUP_PRODUCT_ID=[0-9][0-9]*' /tmp/setup.log | tail -n 1 | cut -d= -f2)"
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
