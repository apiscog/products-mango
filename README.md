# Products API

API REST para gestionar productos y precios con vigencia temporal. La solución conserva los cuatro
endpoints obligatorios del reto y prioriza reglas de negocio explícitas, consistencia bajo concurrencia,
consultas acotadas y una ejecución reproducible con PostgreSQL.

## Funcionalidades

- Crear productos.
- Añadir periodos de precio a un producto.
- Consultar el precio vigente en una fecha.
- Consultar el historial completo, ordenado por fecha inicial.
- Validar el contrato HTTP y devolver errores uniformes.
- Impedir solapamientos incluso ante escrituras concurrentes.
- Exponer OpenAPI, Swagger UI y un healthcheck.
- Ejecutar tests rápidos, tests PostgreSQL/end-to-end y un benchmark k6 reproducible.

### Semántica temporal

- `initDate` es obligatoria e inclusiva.
- `endDate` es opcional e inclusiva; `null` significa vigencia indefinida.
- En un intervalo finito debe cumplirse `initDate < endDate`.
- Se permiten huecos entre precios.
- Dos periodos que comparten una fecha se solapan.

Por ejemplo, `[2024-01-01, 2024-06-30]` se solapa con
`[2024-06-30, 2024-12-31]`, pero no con `[2024-07-01, 2024-12-31]`.

## Stack tecnológico

- Java 21.
- Spring Boot 3.5.15.
- Spring Web, Bean Validation, Spring Data JPA y Actuator.
- PostgreSQL 17 y Flyway.
- SpringDoc OpenAPI 2.8.17.
- Maven Wrapper.
- JUnit 5, Mockito y Testcontainers.
- Docker y Docker Compose.
- Grafana k6 1.7.1.

## Arquitectura

Se aplica una arquitectura hexagonal ligera dentro de una única aplicación modular:

```mermaid
flowchart LR
    Client[Cliente] --> Web[REST Controller]
    Web --> Port[ProductUseCases]
    Port --> Service[ProductApplicationService]
    Service --> Repositories[Repository ports]
    Repositories --> Adapters[PostgreSQL adapters]
    Adapters --> Database[(PostgreSQL)]
```

- `domain` contiene Java puro y no depende de Spring, JPA ni HTTP.
- `application` define casos de uso, comandos, resultados y puertos independientes de infraestructura.
- `adapter.in.web` traduce HTTP, valida peticiones y presenta respuestas y errores.
- `adapter.out.persistence` encapsula JPA, SQL PostgreSQL y el mapeo con el dominio.

Las entidades JPA nunca salen del adaptador de persistencia. `Product` no contiene una colección de
precios, y `PriceJpaEntity` almacena `productId` como un atributo escalar, sin relaciones navegables.
Así, añadir o consultar un precio no carga accidentalmente el historial ni genera problemas N+1.

## Garantía de no solapamiento

El alta de un precio tiene dos niveles de protección:

1. El servicio hace una consulta `EXISTS` sobre los periodos del producto para responder de forma
   temprana con `409 PRICE_OVERLAP`.
2. PostgreSQL aplica una restricción `EXCLUDE` como garantía definitiva cuando dos transacciones
   concurrentes superan simultáneamente la prevalidación.

La API expresa `[initDate, endDate]`. PostgreSQL la representa mediante una columna generada
`validity DATERANGE` como `[initDate, endDate + 1)` o `[initDate, infinity)` cuando `endDate` es null.

La migración habilita `btree_gist` y aplica la exclusión por igualdad de `product_id` y solapamiento
de `validity`. Si PostgreSQL rechaza una carrera con SQLSTATE `23P01` y la restricción
`ex_prices_product_validity`, el adaptador la traduce a `PRICE_OVERLAP`; no se exponen detalles SQL.
La base de datos es la autoridad definitiva de esta invariante.

## Requisitos previos

Para el flujo recomendado solo se necesita Docker con Docker Compose v2 y los puertos locales `8080`
y `5432` disponibles. Java y PostgreSQL locales no son necesarios cuando se utiliza Docker Compose.

## Inicio rápido con Docker Compose

```bash
git clone https://github.com/apiscog/products-mango.git
cd products-mango
java tools/jwt/GenerateDevKeys.java
docker compose up -d --build postgres app
docker compose ps
```

Compose levanta PostgreSQL 17, espera a que esté saludable y después inicia la API. Flyway crea el
esquema automáticamente. La aplicación se empaqueta con Maven en una imagen propia multi-stage.

| Servicio | Dirección |
|---|---|
| API | <http://localhost:8080> |
| Health | <http://localhost:8080/actuator/health> |
| Swagger UI | <http://localhost:8080/swagger-ui/index.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |
| PostgreSQL | `localhost:5432` |

PostgreSQL utiliza un volumen persistente. Para detener los servicios conservando los datos:

```bash
docker compose down
```

Para eliminar también los datos locales:

```bash
docker compose down -v
```

## Bonus de seguridad JWT (esta rama)

La rama **feature/jwt-security** anade Spring Security OAuth2 Resource Server. **master** conserva la
entrega base sin autenticacion. Products API valida Bearer tokens RS256 y scopes antes de llegar al
controlador; no emite tokens, no gestiona usuarios o contrasenas y no es un Authorization Server.

~~~text
Cliente -> Spring Security / JWT -> Products API -> PostgreSQL
~~~

| Acceso | Regla |
|---|---|
| Publico | GET health, OpenAPI y Swagger UI |
| Lectura | scope products.read para GET /products/** |
| Escritura | scope products.write para POST /products/** |

El claim JWT scope se convierte en SCOPE_products.read o SCOPE_products.write. El writer de
demostracion incluye ambos scopes; write no implica read de forma automatica. La API es stateless,
no crea JSESSIONID y devuelve JSON 401 para token ausente/invalido y 403 para scope insuficiente.

Cada clon genera su propio par RSA local, **solo para desarrollo y no reutilizable en produccion**:

~~~text
GenerateDevKeys.java
|- genera tools/jwt/generated/dev-private-key.pem
+- genera config/jwt/generated/dev-public-key.pem

GenerateToken.java -> usa la privada para firmar
Products API       -> usa la publica para validar
~~~

Desde la raiz, antes de arrancar la aplicacion:

~~~bash
java tools/jwt/GenerateDevKeys.java
docker compose up -d --build postgres app
~~~

La utilidad intenta aplicar permisos de lectura/escritura solo para el propietario a la privada en
sistemas POSIX. En Windows avisa si ese modo no está disponible, pero genera el par correctamente.

En PowerShell, genera y copia los tokens:

~~~powershell
$writerToken = java tools/jwt/GenerateToken.java writer
$writerToken | Set-Clipboard
$readerToken = java tools/jwt/GenerateToken.java reader
$readerToken | Set-Clipboard
~~~

En macOS o Linux:

~~~bash
writer_token="$(java tools/jwt/GenerateToken.java writer)"
reader_token="$(java tools/jwt/GenerateToken.java reader)"
printf '%s\n' "$writer_token"
# macOS:
printf %s "$writer_token" | pbcopy
~~~

Los tokens incluyen issuer products-challenge-dev, audience products-api, expiracion aproximada de
15 minutos y los scopes reader o writer. El generador imprime solo el token solicitado y no lo
guarda. En Swagger UI pulsa **Authorize** y pega solo el JWT: el esquema HTTP Bearer anade el prefijo.

Ejemplos minimos:

~~~bash
curl -H "Authorization: Bearer $reader_token" \
  'http://localhost:8080/products/1/prices?date=2024-04-15'

curl -X POST http://localhost:8080/products \
  -H "Authorization: Bearer $writer_token" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Zapatillas deportivas","description":"Modelo 2025 edicion limitada"}'
~~~

Configuracion externalizable:

| Variable | Desarrollo |
|---|---|
| JWT_PUBLIC_KEY_LOCATION | file:./config/jwt/generated/dev-public-key.pem |
| JWT_ISSUER | products-challenge-dev |
| JWT_AUDIENCE | products-api |
| JWT_PRIVATE_KEY_LOCATION | tools/jwt/generated/dev-private-key.pem (solo generador) |
| JWT_TOKEN_TTL_SECONDS | 900 (solo generador, maximo 3600) |

En produccion debe montarse una clave publica externa, por ejemplo
file:/run/secrets/products-public-key.pem, usar HTTPS y delegar emision, custodia de clave privada,
rotacion y revocacion en un proveedor de identidad. No debe copiarse la clave privada al contenedor.
Los detalles de pruebas y coste medido estan en
[docs/jwt-security-results.md](docs/jwt-security-results.md).

### Solución de problemas de claves

Si falta la clave pública o la privada, ejecuta desde la raíz:

~~~bash
java tools/jwt/GenerateDevKeys.java
~~~

Si solo existe una de las dos, el par está incompleto y la utilidad falla sin modificar el archivo
restante. Elimina conscientemente ambos archivos o regenera el par completo:

~~~bash
java tools/jwt/GenerateDevKeys.java --force
~~~

La opción **--force** sustituye ambas claves como una unidad. Los tokens emitidos con el par anterior
dejan de ser válidos porque Products API pasa a verificar con una clave pública nueva.

## API y ejemplos

Las fechas usan el formato ISO `yyyy-MM-dd`. Los comandos están escritos para Bash; en PowerShell se
recomienda `Invoke-RestMethod` con un body generado mediante `ConvertTo-Json`.

| Método | Ruta | Resultado de éxito |
|---|---|---|
| `POST` | `/products` | `201 Created` |
| `POST` | `/products/{id}/prices` | `201 Created` |
| `GET` | `/products/{id}/prices?date=YYYY-MM-DD` | `200 OK` |
| `GET` | `/products/{id}/prices` | `200 OK` |

### Crear un producto

```bash
curl -i -X POST http://localhost:8080/products \
  -H "Authorization: Bearer $writer_token" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Zapatillas deportivas","description":"Modelo 2025 edición limitada"}'
```

Respuesta `201 Created`, con la cabecera `Location: /products/1`:

```json
{
  "id": 1,
  "name": "Zapatillas deportivas",
  "description": "Modelo 2025 edición limitada"
}
```

### Añadir un precio

```bash
curl -i -X POST http://localhost:8080/products/1/prices \
  -H "Authorization: Bearer $writer_token" \
  -H 'Content-Type: application/json' \
  -d '{"value":99.99,"initDate":"2024-01-01","endDate":"2024-06-30"}'
```

Respuesta `201 Created`:

```json
{
  "value": 99.99,
  "initDate": "2024-01-01",
  "endDate": "2024-06-30"
}
```

Un precio indefinido se crea con `"endDate": null`:

```bash
curl -i -X POST http://localhost:8080/products/1/prices \
  -H "Authorization: Bearer $writer_token" \
  -H 'Content-Type: application/json' \
  -d '{"value":129.99,"initDate":"2024-07-01","endDate":null}'
```

### Consultar el precio vigente

```bash
curl -H "Authorization: Bearer $reader_token" \
  'http://localhost:8080/products/1/prices?date=2024-04-15'
```

Respuesta `200 OK`:

```json
{
  "value": 99.99
}
```

### Consultar el historial

```bash
curl -H "Authorization: Bearer $reader_token" http://localhost:8080/products/1/prices
```

Respuesta `200 OK`:

```json
{
  "name": "Zapatillas deportivas",
  "description": "Modelo 2025 edición limitada",
  "prices": [
    {
      "value": 99.99,
      "initDate": "2024-01-01",
      "endDate": "2024-06-30"
    },
    {
      "value": 129.99,
      "initDate": "2024-07-01",
      "endDate": null
    }
  ]
}
```

El historial devuelve `"prices": []` cuando el producto todavía no tiene precios.

## Manejo de errores

Todos los errores controlados siguen esta estructura:

```json
{
  "timestamp": "2026-07-18T12:00:00Z",
  "status": 409,
  "code": "PRICE_OVERLAP",
  "message": "The price period overlaps an existing price",
  "path": "/products/1/prices",
  "violations": []
}
```

| HTTP | Código | Significado |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Parámetros o reglas de dominio inválidos |
| 400 | `MALFORMED_REQUEST` | JSON ausente, ilegible o con tipos inválidos |
| 404 | `PRODUCT_NOT_FOUND` | El producto no existe |
| 404 | `PRICE_NOT_FOUND` | El producto existe, pero no tiene precio para la fecha |
| 409 | `PRICE_OVERLAP` | El periodo se solapa con otro precio del producto |
| 500 | `INTERNAL_ERROR` | Error inesperado, sin detalles internos en la respuesta |

La capa de seguridad usa el mismo envelope: 401 UNAUTHORIZED para token ausente o inválido y
403 FORBIDDEN para un JWT válido sin el scope requerido. Nunca incluye el token, claves, stack trace
o detalles criptográficos.

Un error de validación añade los campos afectados:

```json
{
  "timestamp": "2026-07-18T12:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/products",
  "violations": [
    {
      "field": "name",
      "message": "must not be blank"
    }
  ]
}
```

## Ejecución local de la aplicación

Esta alternativa requiere Java 21 y un PostgreSQL 17 accesible. Con los valores predeterminados se
espera una base `products` en `localhost:5432`, con usuario y contraseña `products`. La conexión puede
externalizarse sin cambiar el artefacto:

| Variable | Valor local predeterminado |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/products` |
| `SPRING_DATASOURCE_USERNAME` | `products` |
| `SPRING_DATASOURCE_PASSWORD` | `products` |

Windows: `.\mvnw.cmd spring-boot:run`

Linux/macOS: `./mvnw spring-boot:run`

Flyway se ejecuta al arrancar. Docker Compose sigue siendo la opción recomendada para evaluar la
entrega porque fija la versión de PostgreSQL y evita dependencias externas como Neon.

La seguridad usa además JWT_PUBLIC_KEY_LOCATION (file:./config/jwt/generated/dev-public-key.pem),
JWT_ISSUER (products-challenge-dev) y JWT_AUDIENCE (products-api). Estos valores pueden
externalizarse sin cambiar el artefacto.

## Tests

### Tests rápidos

Windows: `.\mvnw.cmd test`

Linux/macOS: `./mvnw test`

Ejecutan dominio, aplicación y WebMvc sin arrancar Spring Boot completo, PostgreSQL, Docker, Flyway ni
Testcontainers.

### Suite completa

Windows: `.\mvnw.cmd verify`

Linux/macOS: `./mvnw verify`

Además de los tests rápidos, Failsafe ejecuta los tests `*IT` con PostgreSQL real mediante
Testcontainers: migración Flyway, adaptadores de persistencia, restricción de exclusión, concurrencia
y peticiones HTTP end-to-end en un puerto aleatorio. No se utiliza H2 ni Docker Compose en estos tests.

Totales actuales de esta rama:

- 92 tests rápidos, incluidos seguridad WebMvc y generación local de claves.
- 51 ejecuciones de integración, incluidas 13 de JWT real.
- 143 ejecuciones en total.

Estos totales son una referencia y pueden crecer con la suite.

## Benchmark de rendimiento

En esta rama el recorrido medido es k6 -> Spring Security/JWT -> Products API -> PostgreSQL. El
escenario, cantidades, VUs, fechas, orden y thresholds siguen siendo exactamente los de master.

El benchmark mide `k6 -> Products API -> PostgreSQL` y reproduce el escenario de `benchmark.sh`
proporcionado con el challenge. Utiliza `grafana/k6:1.7.1`, no requiere instalar k6 localmente y
permanece bajo el profile `benchmark`.

```bash
export ACCESS_TOKEN="$(java tools/jwt/GenerateToken.java writer)"
docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

El orquestador prepara un único producto con los tres precios originales, valida las fechas
`2024-04-15`, `2024-08-15`, `2025-03-01` y el historial, y después ejecuta secuencialmente:

| Orden | Prueba | Peticiones exactas | VUs predeterminados |
|---:|---|---:|---:|
| 1 | Creación de productos | 1.000 | 100 |
| 2 | Precio en `2024-04-15` | 20.000 | 500 |
| 3 | Historial | 15.000 | 500 |

Los VUs controlan concurrencia, no el total: cada fase usa `shared-iterations` y posee un threshold que
exige exactamente su número de peticiones. No hay mezcla ponderada, arrival rate ni altas de precios
durante la carga. Variables configurables:

| Variable | Predeterminado |
|---|---:|
| `BASE_URL` | `http://app:8080` |
| `PRODUCT_CREATION_VUS` | `100` |
| `PRICE_QUERY_VUS` | `500` |
| `HISTORY_QUERY_VUS` | `500` |

ACCESS_TOKEN es obligatorio y run-benchmark.sh falla antes del setup si falta. El token no se
imprime ni se guarda. En PowerShell:

~~~powershell
$env:ACCESS_TOKEN = java tools/jwt/GenerateToken.java writer
docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
~~~

Ejemplo PowerShell para reducir solo la concurrencia:

```powershell
$env:PRICE_QUERY_VUS='250'; $env:HISTORY_QUERY_VUS='250'; docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
```

k6 valida status, `Content-Type`, JSON y contrato esperado. Muestra duración, throughput, errores,
checks, avg, mediana, p90, p95, p99 y máximo por fase. Los thresholds exigen cero estados inesperados,
5xx, contratos inválidos e iteraciones descartadas; más de 99 % de checks y menos de 1 % de fallos HTTP.

La línea base sin seguridad permanece en [docs/performance-results.md](docs/performance-results.md).
Dos ejecuciones JWT —la segunda conservando el volumen— completaron exactamente
1.000/20.000/15.000 peticiones, 100 % de checks y 0 % de errores en 63 y 57 segundos. Con 500 VUs,
la API utilizó aproximadamente su límite de una CPU; RS256 por petición tuvo un coste medible.
La comparación está en [docs/jwt-security-results.md](docs/jwt-security-results.md); no es un SLA.
Cambiar de claves fijas a un par local generado no altera RS256 ni el coste por petición, por lo que
estas cifras no se repitieron.

Cada ejecución añade datos únicos al volumen. Para repetir desde una base vacía, debe ejecutarse
explícitamente `docker compose down -v`; el script k6 no elimina datos ni accede directamente a
PostgreSQL.

## Decisiones técnicas

- **Java 21 y Spring Boot 3.5.15:** versión LTS del lenguaje y línea estable compatible del framework.
- **PostgreSQL 17:** permite probar las mismas reglas, rangos e índices en local, CI y evaluación; no
  se sustituye por una base embebida.
- **Flyway y `ddl-auto=validate`:** el esquema es explícito, versionado y validado por Hibernate, no
  generado implícitamente.
- **`BigDecimal` y `NUMERIC(19,2)`:** preservan el valor exacto; se rechazan más de dos decimales y no
  se redondea silenciosamente.
- **`LocalDate`:** la vigencia pertenece a días de calendario y no necesita hora ni zona horaria.
- **Arquitectura hexagonal ligera:** separa negocio e infraestructura sin crear interfaces
  ceremoniales para cada clase.
- **Sin relaciones JPA navegables:** evita cargar historiales o productos cuando la operación solo
  necesita un valor o un `productId`.
- **Consultas específicas:** el precio vigente proyecta únicamente `value`; el historial se recupera
  ordenado por `initDate` e `id`.
- **Exclusión PostgreSQL:** mantiene la invariante de solapamiento bajo concurrencia, donde una
  comprobación Java aislada no sería suficiente.
- **Tests con PostgreSQL real:** Testcontainers ejecuta la misma imagen y migración que la aplicación.
- **k6:** conserva las cantidades del Bash original con concurrencia controlada, fases secuenciales,
  métricas separadas y exit codes reproducibles desde Compose.
- **Sin optimización preventiva:** la medición no mostró errores ni saturación, por lo que no se
  alteraron HikariCP, JVM, SQL o índices sin evidencia.

## Estructura del proyecto

```text
src/main/java/com/mango/products
├── domain                         # Modelo e invariantes en Java puro
├── application                    # Casos de uso, comandos, resultados y puertos
└── adapter
    ├── in/web                     # REST, DTO, validación, errores y OpenAPI
    └── out/persistence            # Entidades JPA, mappers, repositorios y SQL PostgreSQL

src/main/resources
└── db/migration                   # Esquema versionado con Flyway

src/test                           # Tests rápidos, PostgreSQL y HTTP end-to-end
performance                        # Script k6 reproducible
docs                               # Resultados y análisis de rendimiento
```

## Limitaciones y posibles mejoras

Esta rama implementa los cuatro endpoints obligatorios, Swagger/OpenAPI, el benchmark y el bonus JWT.
No incluye gestión de usuarios, emisión o revocación inmediata de tokens, moneda, paginación,
actualización o borrado.

Si el producto evolucionara, podrían valorarse:

- Integración con un proveedor de identidad productivo y rotación de claves.
- Paginación del historial.
- Moneda explícita y sus reglas.
- Actualización o eliminación de precios.
- Observabilidad y alertas.
- Despliegue cloud y pipeline CI/CD.
- API Gateway cuando existan varios servicios que justifiquen ese punto de entrada.

Estas opciones no eran necesarias para el challenge y no se presentan como funcionalidades actuales.

## Comandos útiles

| Objetivo | Comando |
|---|---|
| Levantar API y PostgreSQL | `docker compose up -d --build postgres app` |
| Ver estado | `docker compose ps` |
| Ver logs de la API | `docker compose logs app` |
| Detener conservando datos | `docker compose down` |
| Detener y borrar datos | `docker compose down -v` |
| Tests rápidos en Windows | `.\mvnw.cmd test` |
| Suite completa en Windows | `.\mvnw.cmd verify` |
| Generar writer JWT (PowerShell) | `$env:ACCESS_TOKEN = java tools/jwt/GenerateToken.java writer` |
| Benchmark (requiere ACCESS_TOKEN) | `docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark` |
