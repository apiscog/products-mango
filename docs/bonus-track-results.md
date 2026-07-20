# Bonus track integration

## Base

`feature/bonus-track` parte de `master` en `6c5e484e922bab3dd66e322da79f1a2d169deb80`.
Las ramas se integraron mediante merges no fast-forward, en este orden:

1. `feature/exchange-currency`: `cf458ad0e468dc053e78d3ea8e0001fb04af4c69`.
2. `feature/redis-cache`: `1851d60cbc42512e5f3618d66fde5baf1134e16a`.
3. `feature/jwt-security`: `fe0eca4776b1a5e0c5414bddf8a9298a5db38c85`.

`master` y las tres ramas originales permanecen sin modificaciones.

## Funcionalidades

- Precios en EUR, USD, GBP, JPY o CHF; EUR es el default HTTP compatible.
- Conversión histórica opcional del precio vigente mediante un puerto de tipos de cambio.
- Caché Redis de precio original e historial.
- OAuth2 Resource Server stateless con JWT RS256 y scopes `products.read`/`products.write`.
- PostgreSQL 17 como fuente de verdad y garantía concurrente de no solapamiento.

## Arquitectura final

~~~text
Cliente / k6
  -> Spring Security / JWT
      -> Products API
          -> Redis (lecturas originales)
          -> PostgreSQL (fuente de verdad)
          -> ExchangeRateProvider (solo conversiones opcionales)
~~~

La seguridad queda en el adaptador web. El dominio no conoce Spring, JWT, Redis, HTTP ni el proveedor
externo. Redis no sustituye PostgreSQL y el proveedor de divisas no recibe el Bearer token del cliente.

## Flujo de lectura original

`GET /products/{id}/prices?date=...` atraviesa `CachedPriceQueryService`. La clave es
`products::current-price::<productId>::v<version>::<date>` y el valor JSON contiene importe y
`CurrencyCode` originales. Un miss consulta PostgreSQL; un hit evita la segunda consulta.

`GET /products/{id}/prices` usa
`products::price-history::<productId>::v<version>` y conserva las monedas originales de cada fila.
Los TTL son cinco minutos para precio vigente y dos minutos para historial.

## Flujo de conversión

`GET /products/{id}/prices?date=...&currency=USD` requiere `products.read`. Primero obtiene el precio
original mediante el servicio cacheable y luego calcula la conversión bajo demanda. La respuesta
convertida no se almacena en Redis y no existe caché de tipos de cambio. Si origen y destino coinciden,
se usa tasa 1 sin llamada externa.

El proveedor principal es jsDelivr y el fallback oficial es Cloudflare Pages. Los timeouts
predeterminados son 2 s de conexión y 3 s de lectura. Si ambos fallan se devuelve 503 sin exponer
URLs ni cuerpos externos.

## Flujo de escritura e invalidación

`POST /products/{id}/prices` requiere `products.write`. Tras confirmar la transacción PostgreSQL se
incrementa la versión Redis del producto. Las claves anteriores dejan de ser direccionables y expiran
por TTL. Esto invalida conjuntamente precio vigente e historial para EUR, USD o cualquier moneda
soportada. Si Redis falla, el error de caché se registra y la operación de negocio continúa.

La moneda no forma parte de `ex_prices_product_validity`: sigue existiendo un único precio vigente
por producto y fecha, aunque dos intervalos utilicen monedas distintas.

## Seguridad

- Firma exclusivamente RS256.
- Validación de `exp` obligatorio, `nbf`, issuer y audience.
- GET bajo `/products/**` requiere `products.read`.
- POST bajo `/products/**` requiere `products.write`.
- Health, OpenAPI y Swagger son públicos; el fallback es `denyAll`.
- 401 y 403 se producen antes del controlador con JSON estable.
- La API solo monta la clave pública. La privada permanece en `tools/jwt/generated` y solo la usa
  `GenerateToken.java`.

## Docker

Compose contiene `postgres`, `redis`, `app` y `benchmark` bajo profile. La aplicación espera PostgreSQL
y Redis saludables al arrancar, recibe las propiedades exchange y monta en modo read-only:

~~~text
./config/jwt/generated/dev-public-key.pem
  -> /run/config/products-public-key.pem
~~~

La clave privada no entra en el contexto de build, imagen, JAR, variables ni volúmenes de app.

## Flyway

- V1 crea productos, precios, `validity` y la exclusión temporal.
- V2 añade `currency VARCHAR(3)`, migra datos legacy a EUR y aplica `NOT NULL` y
  `chk_prices_currency`.

`CurrencyMigrationIT` valida una migración PostgreSQL real V1 -> V2. Redis y JWT no alteran el
esquema.

## Benchmark

El benchmark conserva el escenario original y exige un writer token:

- setup funcional con un producto, tres precios EUR, tres fechas e historial;
- 1.000 POST de producto con 100 VUs;
- 20.000 GET de precio para `2024-04-15` con 500 VUs;
- 15.000 GET de historial con 500 VUs.

Las fases son secuenciales y usan `shared-iterations`. No hay conversiones, otras monedas, llamadas al
proveedor ni fases nuevas. Redis está activo y todas las peticiones protegidas incluyen
`Authorization: Bearer <ACCESS_TOKEN>`.

## Tests

La integración cubre dominio monetario, fallback HTTP local, migración V1 -> V2, serialización Redis
de `CurrencyCode`, invalidación, fail-open, JWT real, autorización WebMvc y E2E HTTP. Los tests del
proveedor usan un servidor local simulado y no dependen de Internet.

Resultados del estado integrado:

- 124 tests rápidos, sin contenedores.
- 63 tests de integración con PostgreSQL/Redis Testcontainers cuando corresponde.
- 187 tests totales.

## Decisiones de integración

- Solo el precio original y el historial se cachean.
- Las conversiones y tasas nunca se cachean.
- `CurrencyCode` se serializa como JSON mediante el mapper privado de Redis.
- La conversión atraviesa un servicio de consulta cacheable separado para evitar auto-invocación.
- JWT protege la conversión con el mismo scope `products.read`.
- El benchmark continúa midiendo el contrato original en EUR.
- PostgreSQL sigue siendo la autoridad de vigencia y solapamiento.

## Limitaciones

- El proveedor gratuito de divisas no ofrece SLA.
- No existe caché de tasas ni circuit breaker.
- Se soportan exactamente cinco monedas.
- JPY mantiene dos decimales como simplificación.
- Redis es fail-open durante la ejecución, aunque Compose exige Redis saludable para arrancar app.
- Las claves y el emisor local son exclusivamente de desarrollo.
- Products API no es un Authorization Server y no ofrece revocación inmediata.

## Ejecución local

~~~bash
java tools/jwt/GenerateDevKeys.java
docker compose up -d --build postgres redis app
~~~

PowerShell:

~~~powershell
$readerToken = java tools/jwt/GenerateToken.java reader
$writerToken = java tools/jwt/GenerateToken.java writer
~~~

Bash:

~~~bash
reader_token="$(java tools/jwt/GenerateToken.java reader)"
writer_token="$(java tools/jwt/GenerateToken.java writer)"
~~~

Swagger está en `http://localhost:8080/swagger-ui/index.html`. Para el benchmark:

~~~bash
export ACCESS_TOKEN="$(java tools/jwt/GenerateToken.java writer)"
docker compose --profile benchmark up --build --abort-on-container-exit --exit-code-from benchmark
~~~
