# Redis cache benchmark results

## Objetivo

Esta rama bonus compara el mismo benchmark secuencial en dos arquitecturas:

```text
master:              k6 -> Products API -> PostgreSQL
feature/redis-cache: k6 -> Products API -> Redis / PostgreSQL
```

PostgreSQL continúa siendo la fuente de verdad. Redis solo almacena resultados correctos de las dos consultas y no modifica el contrato HTTP, el esquema SQL ni el perfil k6. La línea base procede sin alteraciones de [`performance-results.md`](performance-results.md).

## Escenario

El setup espera el healthcheck, crea un único producto `Zapatillas deportivas`, añade exactamente los tres precios originales (`99.99`, `129.99` y `199.99`) y valida las fechas `2024-04-15`, `2024-08-15`, `2025-03-01` y el historial. El ID devuelto por la API se comparte con las lecturas.

Después se ejecutan, sin mezcla y en este orden:

| Fase | Peticiones exactas | VUs | Datos |
|---|---:|---:|---|
| Product creation | 1.000 | 100 | productos únicos, sin precios |
| Price query | 20.000 | 500 | mismo producto, fecha `2024-04-15`, valor esperado `99.99` |
| History query | 15.000 | 500 | mismo producto y los tres precios esperados |

Las fases usan `shared-iterations`: los VUs controlan concurrencia, no cantidades. No hay reparto 75/15/5/5, arrival rate, escenario de alta de precios, fechas aleatorias ni pruebas adicionales.

## Configuración Redis

- Imagen: `redis:7.4.9-alpine`.
- Caché `current-price`: clave `<productId>::v<version>::<date>`, TTL 5 minutos.
- Caché `price-history`: clave `<productId>::v<version>`, TTL 2 minutos.
- Prefijo `products::`; versión por producto en `products::cache-version::<productId>`.
- Valores JSON con un `ObjectMapper` exclusivo para Redis, Jackson 2 y soporte de `LocalDate`.
- Una escritura confirmada incrementa la versión después del commit; las claves antiguas quedan inaccesibles y expiran por TTL.
- El manejador fail-open registra `products.cache.errors` y continúa contra PostgreSQL si Redis falla.
- No se emplean `KEYS`, `SCAN`, serialización Java nativa ni transacciones distribuidas.

## Entorno

- Fecha: 2026-07-19.
- Host: Windows 10 Home 64-bit, versión 10.0.19045.
- Docker Desktop 4.82.0; engine 29.6.1, Linux x86_64.
- Recursos visibles a Docker: 12 CPUs y aproximadamente 7,32 GiB.
- Límites Compose: API 1 CPU/1 GiB; PostgreSQL 0,5 CPU/1 GiB; Redis 0,5 CPU/256 MiB; k6 1 CPU/1 GiB.
- VUs: 100/500/500, idénticos a master.

## Línea base master

Estas cifras son las dos ejecuciones actuales de `docs/performance-results.md`; ambas realizaron 1.000/20.000/15.000 peticiones, sin errores, con 100/500/500 VUs.

| Ejecución | Fase | Duración | Throughput | p50 | p95 | p99 | Máximo |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | Product creation | 3 s | 379,65 req/s | 208,97 ms | 498,26 ms | 692,19 ms | 998,40 ms |
| 1 | Price query | 18 s | 1.118,97 req/s | 299,40 ms | 1,10 s | 1,69 s | 4,29 s |
| 1 | History query | 16 s | 949,08 req/s | 400,62 ms | 1,12 s | 1,62 s | 2,99 s |
| 2 | Product creation | 3 s | 396,92 req/s | 205,70 ms | 498,65 ms | 699,43 ms | 1,00 s |
| 2 | Price query | 19 s | 1.081,80 req/s | 301,54 ms | 1,10 s | 1,80 s | 4,19 s |
| 2 | History query | 16 s | 914,83 req/s | 406,39 ms | 1,20 s | 1,70 s | 3,90 s |

## Ejecución Redis 1

Volumen limpio; setup product ID `1`.

| Fase | Peticiones | VUs | Duración | Throughput | avg | p50 | p95 | p99 | Máximo | Errores |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Product creation | 1.000 | 100 | 3 s | 357,67 req/s | 261,17 ms | 290,69 ms | 500,38 ms | 704,34 ms | 892,60 ms | 0% |
| Price query | 20.000 | 500 | 15 s | 1.316,56 req/s | 371,55 ms | 305,39 ms | 692,86 ms | 928,95 ms | 1,49 s | 0% |
| History query | 15.000 | 500 | 9 s | 1.896,43 req/s | 255,70 ms | 284,36 ms | 313,56 ms | 394,86 ms | 484,02 ms | 0% |

- Duración total: 28 s.
- Checks: setup 44/44; creación 5.000/5.000; precio 100.000/100.000; historial 75.000/75.000.
- Códigos inesperados, 5xx e iteraciones descartadas: 0.
- Exit code: 0.

## Ejecución Redis 2

Mismo volumen conservado; el setup obtuvo un nuevo ID (`1002`).

| Fase | Peticiones | VUs | Duración | Throughput | avg | p50 | p95 | p99 | Máximo | Errores |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Product creation | 1.000 | 100 | 3 s | 381,87 req/s | 249,98 ms | 208,04 ms | 504,01 ms | 709,40 ms | 1,00 s | 0% |
| Price query | 20.000 | 500 | 15 s | 1.324,10 req/s | 363,00 ms | 306,83 ms | 603,72 ms | 741,15 ms | 1,07 s | 0% |
| History query | 15.000 | 500 | 8 s | 1.924,35 req/s | 251,47 ms | 278,64 ms | 309,66 ms | 392,88 ms | 425,04 ms | 0% |

- Duración total: 27 s.
- Checks: setup 44/44; creación 5.000/5.000; precio 100.000/100.000; historial 75.000/75.000.
- Códigos inesperados, 5xx e iteraciones descartadas: 0.
- Exit code: 0.

## Comparación master vs Redis

La mejora de latencia se calcula como `(base - redis) / base * 100`; un valor negativo es un empeoramiento. El cambio de throughput usa `(redis - base) / base * 100`. Se compara cada ejecución con la equivalente (volumen limpio o conservado).

| Ejecución | Fase | Δ p50 | Δ p95 | Δ p99 | Δ throughput |
|---:|---|---:|---:|---:|---:|
| 1 | Product creation | -39,1% | -0,4% | -1,8% | -5,8% |
| 1 | Price query | -2,0% | +37,0% | +45,0% | +17,7% |
| 1 | History query | +29,0% | +72,0% | +75,6% | +99,8% |
| 2 | Product creation | -1,1% | -1,1% | -1,4% | -3,8% |
| 2 | Price query | -1,8% | +45,1% | +58,8% | +22,4% |
| 2 | History query | +31,4% | +74,2% | +76,9% | +110,3% |

La mediana de precio no mejoró, pero p95/p99 sí se redujeron de forma marcada. En historial mejoraron tanto la latencia típica como la cola larga. `shared-iterations` relaciona throughput y latencia, por lo que el throughput no se interpreta aislado.

## Recursos

Muestras puntuales de la primera ejecución Redis:

| Fase | API CPU/memoria | PostgreSQL CPU/memoria | Redis CPU/memoria | k6 CPU/memoria |
|---|---|---|---|---|
| Product creation | 100,04% / 381,9 MiB | 8,95% / 77,1 MiB | 2,31% / 11,09 MiB | 12,95% / 47,89 MiB |
| Price query | 104,71% / 447,6 MiB | 0,02% / 76,85 MiB | 3,99% / 11,09 MiB | 18,98% / 195,1 MiB |
| History query | 104,10% / 465,2 MiB | 0,02% / 78,11 MiB | 4,26% / 11,28 MiB | 52,43% / 239,9 MiB |

La API siguió limitada por aproximadamente una CPU. En las lecturas, la muestra de PostgreSQL bajó desde 10,86%/19,27% en master a 0,02%, a cambio de unos 11 MiB y hasta 4,26% de CPU en Redis. Son muestras instantáneas, no máximos. En la segunda ejecución las fases terminaron antes de las ventanas del muestreador y no se atribuyen lecturas `0B` posteriores a contenedores ya detenidos.

## Hit ratio

Antes de la medición se hizo una comprobación controlada: la primera consulta de cada caché produjo un miss, la segunda un hit y las respuestas HTTP fueron idénticas. Actuator registró `current-price` 1 hit/1 miss y `price-history` 1 hit/1 miss (50% en esa muestra deliberadamente mínima), y Redis mostró las claves legibles esperadas.

Durante las fases de 500 VUs, los intentos de consultar Actuator agotaron el timeout mientras la API consumía su CPU asignada. Por ello no se publica un hit ratio numérico del benchmark: inferirlo de las 20.000/15.000 respuestas correctas sería inventar una medición. La CPU casi nula de PostgreSQL, la actividad de Redis y la reducción de p95/p99 son evidencia coherente con caché caliente, pero no sustituyen un contador fiable. Las métricas previas se reiniciaron al recrear los contenedores y no se mezclan con el benchmark.

## Fail-open

Tras el benchmark se creó un producto independiente, se cargaron precio e historial, y se detuvo Redis. Ambas consultas siguieron respondiendo `200` con el mismo valor desde PostgreSQL. El contador `products.cache.errors` registró 6 operaciones fallidas; después de reiniciar Redis se obtuvo `PONG` y la API continuó respondiendo. En Compose, Redis healthy sigue siendo requisito para arrancar la aplicación, aunque una caída posterior no interrumpe las lecturas.

## Análisis

Redis no aporta una mejora directa a product creation porque esa operación no se cachea; sus cambios están dentro de la variabilidad local y añaden el coste de otro servicio. Price query redujo la cola larga sin mejorar la mediana, mientras history query mejoró de forma consistente todos los percentiles principales. La descarga observada de PostgreSQL es el beneficio más claro.

El coste es memoria, serialización JSON, operación de Redis y mayor complejidad. Las escrituras siguen en PostgreSQL y además invalidan después del commit mediante versionado; no existe una fase de `add-price` en este benchmark, por fidelidad al escenario original. No se optimizó producción.

## Limitaciones

- Son ejecuciones locales cortas con Docker Desktop y CPU limitada; no constituyen un SLA.
- 500 VUs generan una caché caliente muy concentrada sobre una sola clave por fase.
- Las muestras de recursos no son una serie temporal ni garantizan máximos.
- No se obtuvo un hit ratio fiable durante la carga por timeout de Actuator bajo saturación.
- Redis añade complejidad operacional; las claves versionadas antiguas expiran por TTL.
- Si Redis falla durante una invalidación, puede existir una ventana obsoleta al recuperarse hasta el TTL configurado.
- El escenario no mide el coste de invalidación en una fase de escrituras de precios.

## Conclusión

En este entorno y escenario concreto, Redis descargó PostgreSQL y mejoró de forma clara p95/p99 de las lecturas, especialmente el historial. No mejoró la creación de productos ni la mediana de precio, y añadió unos 11 MiB de memoria más complejidad operacional. Es un bonus útil para accesos repetidos, no un requisito de la solución base ni una mejora universal.
