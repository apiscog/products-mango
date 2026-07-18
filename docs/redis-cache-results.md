# Redis cache benchmark

## Objective and architecture

This bonus branch measures the existing workload after adding a distributed cache without changing
the HTTP contract, SQL schema or k6 profile:

```text
k6 -> Products API -> Redis Cache -> PostgreSQL (source of truth)
```

Only successful `getPriceAtDate` and `getPriceHistory` results are cached. Writes always go to
PostgreSQL. The comparison uses the preserved-volume reference run in
[`performance-results.md`](performance-results.md), executed on the same local environment and with
the same default 65-second k6 load profile.

## Cache configuration

| Cache | Logical key | TTL |
|---|---|---:|
| `current-price` | `<productId>::v<version>::<date>` | 5 minutes |
| `price-history` | `<productId>::v<version>` | 2 minutes |

Redis keys have the `products::` prefix. The product version is stored as
`products::cache-version::<productId>`, producing readable entries such as
`products::current-price::1::v2::2030-01-01`.

After a successful `addPrice` transaction, the product version is incremented. New reads therefore
cannot see entries from the previous version. This invalidates only the affected product and avoids
`KEYS`, `SCAN` in the request path, and global cache eviction. Superseded values remain unreachable
until their short TTL expires.

Values use cache-specific Jackson 2 JSON serializers with `JavaTimeModule`; Java native
serialization and global default typing are not used. Redis connection, cache read/write and
invalidation failures are logged with throttling and exposed through a small Micrometer error
counter. Cache operations fail open, so PostgreSQL remains usable while Redis is unavailable. A
version increment that fails during an outage can leave previously cached data usable after Redis
recovers until its TTL; this bounded eventual-consistency window is the explicit fail-open trade-off.

## Reproduction

Image: `redis:7.4.9-alpine`. Benchmark and thresholds are unchanged from the baseline.

```powershell
docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

Default stable target: 20 iterations/s, distributed as 75% current price, 15% history, 5% product
creation and 5% price creation. The complete ramp lasts 65 seconds. Date of the cached run:
2026-07-18.

## Results

| Metric | PostgreSQL baseline | Redis branch | Change |
|---|---:|---:|---:|
| HTTP requests | 1,080 | 1,079 | -1 |
| HTTP throughput | 16.29 req/s | 16.28 req/s | -0.1% |
| Load iterations | 955 | 954 | -1 |
| Failed HTTP requests | 0.00% | 0.00% | unchanged |
| Checks | 5,023/5,023 | 5,018/5,018 | 100% in both |
| Dropped iterations | 0 | 0 | unchanged |
| Benchmark exit code | 0 | 0 | thresholds passed |

| Trend | Baseline avg | Redis avg | Baseline p95 | Redis p95 | Redis p99 | Redis max |
|---|---:|---:|---:|---:|---:|---:|
| All HTTP | 3.96 ms | 3.46 ms | 5.24 ms | 5.44 ms | 17.25 ms | 486.28 ms |
| Current price | 2.73 ms | 2.28 ms | 3.75 ms | 3.34 ms | 6.07 ms | 23.77 ms |
| History | 4.09 ms | 2.49 ms | 5.61 ms | 5.52 ms | 10.22 ms | 29.83 ms |
| Writes | 4.17 ms | 4.67 ms | 5.45 ms | 6.44 ms | 13.90 ms | 15.41 ms |

A mid-load Actuator sample reported 437 current-price hits and 24 misses: an observed hit ratio of
approximately 94.8%. This confirms that the existing k6 data reuse actually exercises the cache.
It is a point-in-time application metric, not a production hit-ratio forecast.

## Resource sample

`docker stats --no-stream` during the stable phase:

| Container | CPU | Memory |
|---|---:|---:|
| Products API | 5.11% | 314.7 MiB |
| PostgreSQL | 0.13% | 57.15 MiB |
| Redis | 0.40% | 8.43 MiB |
| k6 | 1.62% | 20.57 MiB |

These are point-in-time samples. They cannot establish peak consumption, and differences from the
baseline sample include normal local scheduling and Docker Desktop noise.

## Analysis

The cached branch preserved throughput, correctness and every threshold. Current-price average and
p95 improved by roughly 16% and 11%; history average improved while its p95 was effectively flat.
Writes became slightly slower because a successful price addition increments the Redis version after
the PostgreSQL commit. At this modest local rate, the absolute changes are small and the aggregate p95
is within measurement noise.

No further production optimization was applied. There is no saturation or failing threshold to
justify changing HikariCP, JVM settings, SQL, indexes or the benchmark. Redis adds operational
complexity and memory in exchange for reducing repeated database reads; whether that trade is useful
in production depends on real traffic, hit ratio and database pressure.

## Limitations

- This is a short local comparison, not a capacity, soak or production-network test.
- The baseline and cached run are separate executions rather than an interleaved controlled trial.
- Resource measurements are individual samples, not time-series peaks.
- Versioned invalidation leaves unreachable entries until TTL expiry and does not reclaim them
  immediately.
- Fail-open prioritizes availability; after an invalidation failure, stale data can reappear on Redis
  recovery for at most the configured TTL.
- Actuator cache statistics are local to the application instance and reset on restart.
