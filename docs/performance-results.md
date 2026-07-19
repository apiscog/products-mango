# Benchmark de rendimiento corregido

## Objetivo

El benchmark conserva k6 como herramienta, pero reproduce exclusivamente las operaciones, datos,
orden y cantidades de `benchmark.sh` incluido originalmente en el challenge:

```text
k6 -> Products API -> PostgreSQL
```

El Bash original lanzaba un proceso `curl` en segundo plano por petición. La traducción a k6 conserva
el volumen funcional y la intención concurrente, sustituyendo los procesos del sistema por VUs
controlables y contadores verificables. Estos resultados son una referencia local, no un SLA.

## Escenario reproducido

### Preparación funcional

Una fase `setup` separada:

1. espera `GET /actuator/health` con respuesta `200` y `status=UP`;
2. crea exactamente un producto `Zapatillas deportivas`;
3. crea exactamente los precios `99.99` (enero-junio 2024), `129.99` (julio-diciembre 2024) y
   `199.99` (desde enero 2025, sin final);
4. consulta exactamente `2024-04-15`, `2024-08-15`, `2025-03-01` y el historial.

El identificador se extrae del JSON y se pasa a las fases de lectura. La preparación genera ocho
peticiones funcionales más una petición de health cuando la aplicación ya está disponible. Sus
métricas se etiquetan como `setup` y no se mezclan con los conteos de carga.

### Fases de carga

Las fases se ejecutan secuencialmente mediante cuatro procesos k6 coordinados por
`performance/run-benchmark.sh`:

| Orden | Fase | Operación | Cantidad exacta | VUs por defecto |
|---:|---|---|---:|---:|
| 1 | `product-creation` | `POST /products` | 1.000 | 100 |
| 2 | `price-query` | `GET /products/{id}/prices?date=2024-04-15` | 20.000 | 500 |
| 3 | `history-query` | `GET /products/{id}/prices` | 15.000 | 500 |

Cada fase usa `shared-iterations`: los VUs reparten trabajo concurrente, pero no cambian el total. No
hay arrival rate, mezcla ponderada, fechas aleatorias ni altas de precios durante la carga. Los 1.000
productos usan `<n>` de 1 a 1.000, son únicos dentro de la ejecución y no reciben precios. Repetirlos
con un volumen conservado es válido porque el nombre de producto no tiene una restricción de unicidad.

## Ejecución

```powershell
docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

Variables configurables:

| Variable | Predeterminado | Efecto |
|---|---:|---|
| `BASE_URL` | `http://app:8080` | API dentro de la red Compose |
| `PRODUCT_CREATION_VUS` | `100` | concurrencia de las 1.000 altas |
| `PRICE_QUERY_VUS` | `500` | concurrencia de las 20.000 consultas de precio |
| `HISTORY_QUERY_VUS` | `500` | concurrencia de los 15.000 historiales |

Ejemplo PowerShell, sin alterar las cantidades:

```powershell
$env:PRICE_QUERY_VUS='250'; $env:HISTORY_QUERY_VUS='250'; docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

Cada ejecución añade datos. La limpieza completa es opcional y explícita:

```powershell
docker compose --profile benchmark down -v
```

## Validaciones y métricas

Cada petición valida el status esperado, Content-Type JSON, JSON legible, contrato mínimo y ausencia
de 5xx. Los contadores `product_creation_requests`, `price_query_requests` y
`history_query_requests` tienen thresholds exactos de 1.000, 20.000 y 15.000.

Thresholds funcionales:

- cero códigos inesperados;
- cero errores 5xx;
- cero contratos inválidos;
- cero iteraciones descartadas;
- más del 99% de checks y éxito de negocio;
- menos del 1% de `http_req_failed` en cada fase de carga.

No se fija un límite de latencia como requisito del challenge. k6 muestra por separado duración,
throughput, avg, mediana, p90, p95, p99 y máximo para cada fase.

## Entorno de referencia

- Fecha: 2026-07-19.
- Host: Windows 10 Home 64-bit, versión 10.0.19045.
- Docker Desktop: engine 29.6.1, Linux x86_64.
- Recursos visibles a Docker: 12 CPUs y aproximadamente 7,32 GiB.
- Límites Compose: API 1 CPU/1 GiB, PostgreSQL 0,5 CPU/1 GiB, k6 1 CPU/1 GiB.
- Imágenes: `postgres:17-alpine` (PostgreSQL 17.10) y `grafana/k6:1.7.1`.
- Concurrencia: 100/500/500 VUs.

## Resultados

### Ejecución 1: volumen limpio

| Fase | Requests | Duración pared | Throughput | Errores | Checks |
|---|---:|---:|---:|---:|---:|
| Product creation | 1.000 | 3 s | 379,65 req/s | 0% | 5.000/5.000 |
| Price query | 20.000 | 18 s | 1.118,97 req/s | 0% | 100.000/100.000 |
| History query | 15.000 | 16 s | 949,08 req/s | 0% | 75.000/75.000 |

| Fase | avg | mediana | p90 | p95 | p99 | máximo |
|---|---:|---:|---:|---:|---:|---:|
| Product creation | 248,21 ms | 208,97 ms | 405,92 ms | 498,26 ms | 692,19 ms | 998,40 ms |
| Price query | 435,51 ms | 299,40 ms | 864,28 ms | 1,10 s | 1,69 s | 4,29 s |
| History query | 513,90 ms | 400,62 ms | 907,46 ms | 1,12 s | 1,62 s | 2,99 s |

- Setup: 8 peticiones funcionales + 1 health; 44/44 checks.
- Carga total exacta: 36.000 peticiones.
- Duración total aproximada: 38 segundos.
- Códigos inesperados, 5xx e iteraciones descartadas: 0.
- Exit code: 0.

Muestras puntuales durante las lecturas:

| Fase | API CPU/memoria | PostgreSQL CPU/memoria | k6 CPU/memoria |
|---|---|---|---|
| Product creation | 100,86% / 336,70 MiB | 8,81% / 76,84 MiB | 13,19% / 50,96 MiB |
| Price query | 104,45% / 365,40 MiB | 10,86% / 80,53 MiB | 13,70% / 179,40 MiB |
| History query | 103,32% / 422,00 MiB | 19,27% / 83,05 MiB | 25,94% / 197,20 MiB |

### Ejecución 2: volumen conservado

El setup recuperó un nuevo ID (`1002`), demostrando repetibilidad sin limpiar PostgreSQL.

| Fase | Requests | Duración pared | Throughput | Errores | Checks |
|---|---:|---:|---:|---:|---:|
| Product creation | 1.000 | 3 s | 396,92 req/s | 0% | 5.000/5.000 |
| Price query | 20.000 | 19 s | 1.081,80 req/s | 0% | 100.000/100.000 |
| History query | 15.000 | 16 s | 914,83 req/s | 0% | 75.000/75.000 |

| Fase | avg | mediana | p90 | p95 | p99 | máximo |
|---|---:|---:|---:|---:|---:|---:|
| Product creation | 236,07 ms | 205,70 ms | 404,61 ms | 498,65 ms | 699,43 ms | 1,00 s |
| Price query | 451,07 ms | 301,54 ms | 895,46 ms | 1,10 s | 1,80 s | 4,19 s |
| History query | 532,19 ms | 406,39 ms | 986,42 ms | 1,20 s | 1,70 s | 3,90 s |

- Setup: 8 peticiones funcionales + 1 health; 44/44 checks.
- Carga total exacta: 36.000 peticiones.
- Duración total aproximada: 39 segundos.
- Códigos inesperados, 5xx e iteraciones descartadas: 0.
- Exit code: 0.

Muestras puntuales:

| Fase | API CPU/memoria | PostgreSQL CPU/memoria | k6 CPU/memoria |
|---|---|---|---|
| Product creation | 100,06% / 280,90 MiB | 9,30% / 39,35 MiB | 27,13% / 76,25 MiB |
| Price query | 104,06% / 339,00 MiB | 11,08% / 43,57 MiB | 14,39% / 146,10 MiB |
| History query | 101,20% / 377,70 MiB | 15,80% / 45,09 MiB | 26,79% / 175,20 MiB |

## Análisis

Las dos ejecuciones reprodujeron exactamente las cantidades originales y fueron repetibles sobre un
volumen persistente. Los 500 VUs de lectura mantienen ocupada aproximadamente una CPU completa de la
API, coherente con el límite Compose, pero no provocaron errores, descartes ni respuestas inválidas.
No se modificó producción ni se aplicó ninguna optimización.

## Limitaciones

- Es una prueba local corta, no un soak test ni una estimación universal de capacidad.
- `docker stats` son muestras puntuales y pueden superar ligeramente 100% por el intervalo de muestreo.
- `shared-iterations` conserva el volumen y concurrencia de forma reproducible, pero no intenta iniciar
  literalmente miles de procesos a la vez como el bucle Bash.
- La duración de pared del orquestador se mide con resolución de un segundo; k6 ofrece las tasas y
  latencias precisas por fase.
- Los resultados dependen del hardware, Docker Desktop y carga local.
