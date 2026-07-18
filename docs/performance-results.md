# Performance baseline

## Objective and measured architecture

This benchmark measures the four mandatory API operations through the complete request path:

```text
k6 -> Products API -> PostgreSQL
```

The goal is a reproducible local baseline, not a production SLA. Results depend on the host hardware,
Docker Desktop allocation, background workload, filesystem and operating system.

Grafana k6 was selected because it provides controlled arrival rates, checks, tags, percentiles,
threshold-based exit codes and a small official Docker image. The benchmark pins `grafana/k6:1.7.1`
instead of requiring a local k6 installation.

## How to run

PowerShell, from the repository root:

```powershell
docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

The `benchmark` profile keeps ordinary local startup independent:

```powershell
docker compose up -d postgres app
```

Configuration can be overridden before running Compose:

```powershell
$env:TARGET_RATE='30'; $env:TEST_DURATION='45s'; docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

Supported variables and defaults:

| Variable | Default | Meaning |
|---|---:|---|
| `BASE_URL` | `http://app:8080` | API address inside the Compose network |
| `WARMUP_DURATION` | `10s` | Initial low-rate phase |
| `RAMP_UP_DURATION` | `15s` | Progressive increase to the target |
| `TEST_DURATION` | `30s` | Stable target-rate phase |
| `COOLDOWN_DURATION` | `10s` | Progressive decrease to zero |
| `TARGET_RATE` | `20` | Total target iterations per second; minimum 4 |

The default load phase lasts 65 seconds, plus HTTP setup and a maximum 5-second graceful stop.
The stable target-rate distribution is:

| Scenario | Rate | Distribution |
|---|---:|---:|
| Current price | 15 iterations/s | 75% |
| History | 3 iterations/s | 15% |
| Create product | 1 iteration/s | 5% |
| Add price | 1 iteration/s | 5% |

The warm-up uses at least one iteration/s per scenario, so the distribution over the complete ramp is
approximately 72% / 15% / 6% / 6%. The stable phase is exactly 75% / 15% / 5% / 5%.

## Data preparation and repeatability

`setup()` first verifies `/actuator/health`, then creates eight products with these non-overlapping
periods:

- `2024-01-01` to `2024-06-30`;
- `2024-07-01` to `2024-12-31`;
- `2025-01-01` to `null`.

It also creates a calculated pool of products for the add-price scenario. Each load iteration consumes
a different product, so additions measure successful writes instead of overlap conflicts. All data is
created through the REST API and every run uses a timestamp-based `runId`.

Each execution adds products and prices to PostgreSQL. Repeated execution with the same volume is
supported. For an explicitly clean database, run this manually before the benchmark:

```powershell
docker compose --profile benchmark down -v
```

The k6 script never deletes data and never connects directly to PostgreSQL.

## Checks, metrics and thresholds

Every load operation checks the expected status, JSON content type, valid JSON, required response
fields and absence of 5xx responses. Setup aborts immediately if health, product creation, price
creation or JSON contracts are invalid.

Dynamic IDs are normalized with fixed request names and the tags `endpoint` and `phase`. In addition
to native k6 metrics, the script records:

- `unexpected_status_codes`;
- `server_errors`;
- `business_success`;
- `current_price_duration`;
- `history_duration`;
- `write_duration`.

Initial local thresholds:

| Metric | Threshold |
|---|---|
| `http_req_failed` | rate `< 1%` |
| `checks` | rate `> 99%` |
| `business_success` | rate `> 99%` |
| `server_errors` | count `== 0` |
| `unexpected_status_codes` | count `== 0` |
| `dropped_iterations` | count `== 0` |
| Current-price p95 | `< 500 ms` |
| History p95 | `< 750 ms` |
| Write p95 | `< 1000 ms` |

k6 exits with a non-zero status when any threshold fails.

## Reference environment

- Date: 2026-07-18.
- Host: Microsoft Windows 10 Home 64-bit, version 10.0.19045.
- Docker Desktop engine: 29.6.1, Linux x86_64.
- Resources visible to Docker: 12 CPUs and approximately 7.32 GiB memory.
- Compose limits: app 1 CPU / 1 GiB; PostgreSQL 0.5 CPU / 1 GiB; k6 1 CPU / 1 GiB.
- PostgreSQL image observed: PostgreSQL 17.10 from `postgres:17-alpine`.
- k6 image: `grafana/k6:1.7.1`.

## Results

Both runs used the default profile. Native HTTP totals include the 125 setup requests; iteration totals
represent load scenarios.

### Run 1: clean volume

| Metric | Result |
|---|---:|
| k6 measured duration | 66.4 s |
| HTTP requests | 1,076 |
| HTTP throughput | 16.20 requests/s |
| Load iterations | 951 |
| Failed HTTP requests | 0.00% |
| Checks | 5,003 / 5,003 (100%) |
| Business success | 951 / 951 (100%) |
| Dropped iterations | 0 |
| Unexpected statuses | 0 |
| Server errors | 0 |
| Benchmark exit code | 0 |

| Trend | avg | median | p90 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|
| All HTTP | 4.71 ms | 2.74 ms | 4.68 ms | 6.46 ms | 55.91 ms | 378.55 ms |
| Current price | 2.56 ms | 2.37 ms | 3.45 ms | 3.76 ms | 5.08 ms | 13.12 ms |
| History | 3.82 ms | 3.46 ms | 4.92 ms | 5.23 ms | 6.06 ms | 30.06 ms |
| Writes | 11.53 ms | 4.18 ms | 40.04 ms | 55.12 ms | 62.06 ms | 63.15 ms |

Representative `docker stats --no-stream` samples during load:

| Container | CPU observed | Memory observed |
|---|---:|---:|
| Products API | approximately 1.5-7.1% | approximately 316-322 MiB |
| PostgreSQL | approximately 0.4-0.7% | approximately 55-56 MiB |
| k6 | approximately 0.5-1.8% | approximately 20 MiB |

Application startup reported by Spring took 7.6 seconds. From initial Compose container startup until
the application became healthy and k6 could start setup was approximately 15-17 seconds. This is a
local approximate availability measurement, not a controlled cold-start benchmark.

### Run 2: preserved PostgreSQL volume

| Metric | Result |
|---|---:|
| k6 measured duration | 66.3 s |
| HTTP requests | 1,080 |
| HTTP throughput | 16.29 requests/s |
| Load iterations | 955 |
| Failed HTTP requests | 0.00% |
| Checks | 5,023 / 5,023 (100%) |
| Business success | 955 / 955 (100%) |
| Dropped iterations | 0 |
| Unexpected statuses | 0 |
| Server errors | 0 |
| Benchmark exit code | 0 |

| Trend | avg | median | p90 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|
| All HTTP | 3.96 ms | 2.87 ms | 4.45 ms | 5.24 ms | 9.15 ms | 481.39 ms |
| Current price | 2.73 ms | 2.61 ms | 3.50 ms | 3.75 ms | 4.60 ms | 12.06 ms |
| History | 4.09 ms | 3.66 ms | 5.08 ms | 5.61 ms | 7.49 ms | 27.11 ms |
| Writes | 4.17 ms | 4.07 ms | 5.15 ms | 5.45 ms | 6.30 ms | 7.61 ms |

Representative second-run load sample:

| Container | CPU | Memory |
|---|---:|---:|
| Products API | 8.42% | 271.1 MiB |
| PostgreSQL | 2.77% | 40.42 MiB |
| k6 | 1.92% | 19.93 MiB |

Spring reported 7.2 seconds for application startup. The preserved volume produced no setup conflicts,
unexpected statuses or failed checks.

## Analysis and optimization decision

All thresholds passed with substantial margin in both runs. No request failed, no iteration was
dropped, no unexpected status or 5xx response occurred, and application/PostgreSQL logs contained no
errors. CPU and memory samples show no saturation relative to the configured limits. Read latency was
stable between runs; the first-run write tail was higher but still small and did not reproduce after
the initial database run.

There is therefore no evidence supporting a production optimization in this iteration. HikariCP, JVM
flags, SQL, indexes, logging and application code remain unchanged. This preserves the measured
baseline and follows the rule to optimize only after a reproducible bottleneck is observed.

## Limitations

- This is a short local baseline, not a soak, stress, capacity or production-network test.
- `docker stats` values are point-in-time samples, not peak or time-series measurements.
- Setup requests are included in native aggregate HTTP metrics; custom endpoint trends represent load.
- The arrival-rate profile controls requests, but the minimum one request/s during warm-up slightly
  increases the write share over the complete run.
- Cold-start timing is approximate and includes Docker orchestration and healthcheck polling.
- Results must not be generalized to other hardware or Docker allocations.
