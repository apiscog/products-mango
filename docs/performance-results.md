# Performance validation

Este documento recoge las pruebas de rendimiento realizadas sobre Products API y diferencia claramente entre:

- El benchmark oficial proporcionado con el challenge.
- La prueba de carga complementaria desarrollada con k6.

Ambas herramientas se mantienen separadas porque utilizan modelos de concurrencia distintos y, por tanto, sus resultados no son directamente comparables.

## Benchmark oficial

`performance/benchmark.sh` es el script original incluido en el challenge.

El archivo se conserva sin modificaciones y define el escenario oficial de rendimiento. Su hash Git es:

Para facilitar su ejecución se añadió `performance/run-benchmark.sh`.

Este segundo script actúa únicamente como wrapper:

- Localiza la raíz del repositorio.
- Comprueba que las dependencias necesarias estén disponibles.
- Ejecuta una sola vez el benchmark original mediante:

```bash
exec bash performance/benchmark.sh
```

El wrapper no contiene endpoints, fechas, cantidades ni lógica propia de carga. De esta forma, el escenario continúa definido en un único archivo.

## Escenario ejecutado

El benchmark utiliza la URL fija:

```text
http://product-api:8080
```

El flujo que ejecuta es el siguiente:

1. Espera cinco segundos.
2. Consulta `GET /actuator/health` cada cinco segundos hasta recibir estado `UP`.
3. Crea un producto llamado `Zapatillas deportivas`.
4. Extrae su identificador utilizando `grep`, `cut` y `tr`.
5. Añade tres periodos de precio:
    - 99,99 entre `2024-01-01` y `2024-06-30`.
    - 129,99 entre `2024-07-01` y `2024-12-31`.
    - 199,99 desde `2025-01-01`, sin fecha final.
6. Consulta el precio vigente en:
    - `2024-04-15`.
    - `2024-08-15`.
    - `2025-03-01`.
7. Consulta el historial de precios.
8. Ejecuta 1.000 peticiones `POST /products`.
9. Ejecuta 20.000 consultas de precio para `2024-04-15`.
10. Ejecuta 15.000 consultas de historial.

Las tres fases de carga se ejecutan de forma secuencial.

Dentro de cada fase, el script lanza un proceso `curl` en background por cada petición y espera a que terminen todos.

No existe un límite equivalente a VUs. La concurrencia real depende de la capacidad del sistema operativo para crear y planificar miles de procesos.

Las duraciones se calculan mediante:

- `date +%s.%N`.
- `bc`.

Dependencias requeridas:

- Bash.
- curl.
- grep.
- cut.
- tr.
- date.
- bc.
- sleep.

## Limitaciones del benchmark

El benchmark reproduce exactamente el escenario entregado, pero tiene varias limitaciones que deben tenerse en cuenta al interpretar los resultados.

El script:

- No admite variables de entorno.
- No permite cambiar la URL base.
- No envía cabecera `Authorization`.
- No configura timeout para `curl`.
- No valida de forma general los códigos HTTP.
- No comprueba el contrato JSON de las respuestas.
- Solo verifica que puede extraer el ID del producto inicial.

Por tanto, un exit code `0` confirma que el script finalizó, pero no garantiza que todas las peticiones hayan devuelto una respuesta funcional correcta.

Además, `curl` no considera por defecto una respuesta HTTP 4xx o 5xx como error de proceso. Sin opciones adicionales, una respuesta de este tipo puede producir igualmente exit code `0`.

## Ejecución

En un entorno donde el hostname `product-api` resuelva correctamente:

```bash
bash performance/run-benchmark.sh
```

La URL fija indica que el script fue diseñado para ejecutarse dentro de una red de contenedores.

Para lanzarlo directamente desde el host sería necesario que `product-api` pudiera resolverse. El wrapper no modifica el hostname ni altera el archivo original.

## Compatibilidad con la rama integrada

La rama `master`, incluye seguridad JWT y exige un Bearer token en los endpoints de producto.

El benchmark oficial no envía la cabecera `Authorization`, por lo que no puede ejecutarse contra esta versión sin cambiar el escenario original.

Para mantener la compatibilidad, las mediciones oficiales se realizaron sobre la entrega base sin JWT:

La prueba se ejecutó en un proyecto Docker Compose temporal y aislado.

El mismo archivo `performance/benchmark.sh`, con el hash indicado anteriormente, se montó en modo read-only dentro del runner.

## Entorno de referencia

Las mediciones se realizaron con el siguiente entorno:

- Fecha: 2026-07-21.
- Sistema operativo: Windows 10 `10.0.19045` x64.
- Java: 21.0.2.
- Docker Desktop: 4.82.0.
- Docker Engine: 29.6.1.
- Entorno Docker: Linux x86_64.
- CPU visibles para Docker: 12 CPU lógicas.
- Memoria visible para Docker: 7,32 GiB.
- Límite de la API: 1 CPU y 1 GiB.
- Límite de PostgreSQL: 0,5 CPU y 1 GiB.
- PostgreSQL: `postgres:17-alpine`.
- Tiempo aproximado hasta healthcheck: 16,9 segundos.

## Resultados oficiales

Se realizaron dos ejecuciones consecutivas.

La primera comenzó con un volumen limpio. La segunda reutilizó el volumen y, por tanto, mantuvo los datos, procesos calentados y parte de las cachés del sistema.

### Ejecución 1: volumen limpio

| Fase | Peticiones | Tiempo | Throughput calculado |
|---|---:|---:|---:|
| Creación de productos | 1.000 | 6,987 s | 143,12 req/s |
| Consulta de precio vigente | 20.000 | 83,377 s | 239,87 req/s |
| Consulta de historial | 15.000 | 787,533 s | 19,05 req/s |

Resumen:

- Tiempo total de las fases medidas: 877,897 segundos.
- ID del producto de preparación: `1`.
- Exit code del script: `0`.
- Errores `curl: (n)` detectados: `0`.
- Errores de creación de procesos detectados: `0`.

Picos de recursos observados:

| Servicio | CPU máxima observada | Memoria máxima observada |
|---|---:|---:|
| API | 118,74% | 526 MiB |
| PostgreSQL | 30,93% | 85,21 MiB |
| Runner | 1.123,50% | 6,05 GiB |

El runner quedó claramente saturado.

Esto es relevante porque el resultado no mide únicamente el rendimiento de la API. También está condicionado por el coste de crear y mantener miles de procesos `curl` en paralelo.

### Ejecución 2: volumen conservado

| Fase | Peticiones | Tiempo | Throughput calculado |
|---|---:|---:|---:|
| Creación de productos | 1.000 | 2,002 s | 499,52 req/s |
| Consulta de precio vigente | 20.000 | 22,070 s | 906,19 req/s |
| Consulta de historial | 15.000 | 19,270 s | 778,42 req/s |

Resumen:

- Tiempo total de las fases medidas: 43,342 segundos.
- ID del nuevo producto de preparación: `1002`.
- Exit code del script: `0`.
- Errores `curl: (n)` detectados: `0`.
- Errores de creación de procesos detectados: `0`.

Muestras puntuales de recursos:

| Servicio | CPU máxima observada | Memoria máxima observada |
|---|---:|---:|
| API | 100,93% | 535,1 MiB |
| PostgreSQL | 26,87% | 47,49 MiB |
| Runner | 970,28% | 402,8 MiB |

El muestreo se realizó cada 30 segundos, por lo que el valor de memoria del runner puede no representar el pico real.

## Validación de los datos generados

Después de las dos ejecuciones, PostgreSQL contenía:

- 2.002 productos.
- 6 precios.

El resultado es coherente con las escrituras esperadas:

- Un producto inicial por ejecución.
- 1.000 productos adicionales por ejecución.
- Tres precios para cada producto inicial.

No es posible publicar una cifra fiable de errores HTTP para las consultas de lectura porque el benchmark no registra los status code.

Durante la primera ejecución también aparecieron warnings JDBC en la aplicación mientras el entorno estaba saturado.

Este punto refuerza que el exit code `0` no debe interpretarse como una validación funcional completa.

## Interpretación de los resultados

La diferencia entre ambas ejecuciones es muy alta:

- Primera ejecución: 877,897 segundos.
- Segunda ejecución: 43,342 segundos.

La suma de fases fue aproximadamente veinte veces menor en la segunda prueba.

Esta variación indica que el benchmark es especialmente sensible a:

- Creación y planificación de procesos.
- Presión de memoria.
- Estado de la JVM.
- Cachés del sistema operativo.
- Estado de PostgreSQL.
- Carga general del equipo.
- Condiciones del entorno Docker Desktop.

Por este motivo, estos resultados deben entenderse como mediciones locales de referencia, no como un SLA ni como una capacidad estable del sistema.

Tampoco sería correcto atribuir toda la diferencia a una mejora de la API, ya que el propio generador de carga forma parte importante del cuello de botella.

## Prueba complementaria con k6

`performance/products-load-test.js` implementa una prueba de carga adicional mediante Grafana k6.

Su objetivo no es sustituir el benchmark oficial, sino aportar una ejecución más controlada y con mayor observabilidad.

`performance/run-k6-load-test.sh` se encarga de preparar el entorno y coordinar las fases.

La prueba mantiene:

- Los mismos datos principales.
- Las mismas fechas.
- Las cantidades de 1.000, 20.000 y 15.000 operaciones.

Sin embargo, su metodología es diferente.

### Diferencias respecto al benchmark oficial

k6 utiliza `shared-iterations` y un número controlado de VUs:

- 100 VUs para creación de productos.
- 500 VUs para precio vigente.
- 500 VUs para historial.

Además:

- Valida status HTTP.
- Valida `Content-Type`.
- Comprueba que la respuesta sea JSON.
- Comprueba partes del contrato funcional.
- Exige las cantidades esperadas.
- Detecta status inesperados.
- Registra errores 5xx.
- Detecta iteraciones descartadas.
- Publica media, mediana, p90, p95, p99 y máximo.
- Publica checks y throughput.
- Admite `ACCESS_TOKEN`.
- Puede ejecutarse contra la rama integrada con JWT.
- Comprueba el contrato multidivisa y espera `EUR`.
- Utiliza la aplicación integrada con Redis.

## Ejecución de k6

Linux o macOS:

```bash
export ACCESS_TOKEN="$(java tools/jwt/GenerateToken.java writer)"

docker compose --profile benchmark up \
  --build \
  --abort-on-container-exit \
  --exit-code-from k6
```

PowerShell:

```powershell
$env:ACCESS_TOKEN = java tools/jwt/GenerateToken.java writer

docker compose --profile benchmark up `
  --build `
  --abort-on-container-exit `
  --exit-code-from k6
```

## Resultados históricos de k6

Los siguientes resultados corresponden a una medición realizada el 2026-07-19.

Son anteriores a la decisión de presentar el Bash como benchmark oficial y se conservan como referencia de la prueba complementaria.

| Ejecución | Fase | Throughput | p50 | p95 | p99 | Errores |
|---:|---|---:|---:|---:|---:|---:|
| 1 | Creación | 379,65 req/s | 208,97 ms | 498,26 ms | 692,19 ms | 0% |
| 1 | Precio vigente | 1.118,97 req/s | 299,40 ms | 1,10 s | 1,69 s | 0% |
| 1 | Historial | 949,08 req/s | 400,62 ms | 1,12 s | 1,62 s | 0% |
| 2 | Creación | 396,92 req/s | 205,70 ms | 498,65 ms | 699,43 ms | 0% |
| 2 | Precio vigente | 1.081,80 req/s | 301,54 ms | 1,10 s | 1,80 s | 0% |
| 2 | Historial | 914,83 req/s | 406,39 ms | 1,20 s | 1,70 s | 0% |

En las dos ejecuciones:

- No se registraron errores funcionales.
- No se registraron respuestas 5xx.
- Las cantidades de operaciones fueron las esperadas.
- No se descartaron iteraciones.

Estas cifras pertenecen al modelo de concurrencia controlado de k6.

No representan percentiles del benchmark Bash y no deben compararse directamente con sus tiempos sin explicar previamente la diferencia metodológica.

## Conclusiones

El benchmark oficial se conserva exactamente como fue entregado y sigue siendo la referencia para reproducir el escenario del challenge.

Su principal ventaja es la fidelidad al script original. Su principal limitación es que utiliza una concurrencia basada en procesos `curl` sin control y no valida de forma completa las respuestas HTTP.

El wrapper evita duplicar la definición del escenario y propaga directamente el resultado del script original.

Los tiempos obtenidos permiten calcular throughput mediante:

```text
throughput = número de peticiones / tiempo en segundos
```

Sin embargo, el Bash no permite obtener percentiles, tasas de error HTTP fiables ni métricas de latencia por petición.

La prueba 'run-k6-load-test.sh' complementa esta carencia con un modelo controlado, validaciones funcionales y métricas más útiles para analizar el comportamiento de la API.

En la rama integrada con JWT:

- El benchmark original no es compatible porque envía autenticación, siempre y cuando después de aceptar una pequeña modificación, para que acepte autorización.
- El benchmark modificado `run-k6-load-test.sh` permite ejecutar el escenario utilizando un token válido.

Los bonus de Redis, JWT y conversión de moneda no modifican el benchmark oficial para favorecer artificialmente sus resultados.

## Reproducibilidad

Cada ejecución añade datos al volumen de PostgreSQL.

Para repetir una prueba conservando el estado:

```bash
docker compose down
```

Para eliminar los datos y volver a un entorno limpio:

```bash
docker compose down -v
```

Las comparaciones deben indicar siempre si se parte de un volumen limpio o conservado, ya que esta condición tuvo un impacto importante en los resultados observados.