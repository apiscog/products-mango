# Exchange currency implementation

## Objetivo

Esta rama opcional añade moneda original a cada precio y conversión histórica bajo demanda. Parte
directamente de `master`; no incorpora Redis, JWT ni otras ramas bonus. PostgreSQL continúa siendo la
fuente de verdad del precio y el proveedor externo solo aporta tasas.

## Arquitectura

```text
Cliente
  -> Products API
      -> PostgreSQL (precio original y vigencia)
      -> CurrencyConversionService
          -> ExchangeRateProvider
              -> proveedor principal
              -> fallback
```

El dominio y los puertos no conocen HTTP, URLs, Jackson ni DTO del proveedor. El adaptador
`FawazExchangeRateAdapter` encapsula el transporte y el parsing.

## Dominio

`CurrencyCode` contiene exactamente `EUR`, `USD`, `GBP`, `JPY` y `CHF`. Su fábrica acepta códigos
case-insensitive y emite un error con la lista soportada. `Price` exige una moneda no nula en
`create` y `reconstitute`; no aplica un default implícito ni realiza conversiones.

La moneda no forma parte de `overlaps`. La invariante sigue siendo un único intervalo vigente por
producto y fecha, independientemente de la divisa.

## Persistencia

`V2__add_currency_to_prices.sql`:

1. añade `currency VARCHAR(3)` temporalmente nullable;
2. migra todas las filas V1 existentes a `EUR`;
3. activa `NOT NULL`;
4. añade `chk_prices_currency` para las cinco monedas.

V1, `validity`, el índice histórico y `ex_prices_product_validity` permanecen intactos. JPA usa
`EnumType.STRING`, nunca ordinal.

La prueba `CurrencyMigrationIT` crea una base PostgreSQL 17 en V1, inserta datos legacy, aplica V2 y
comprueba IDs, producto, importe, fechas, EUR, `NOT NULL`, `CHECK` y la exclusión temporal.

## Contratos

La creación acepta `currency` opcional. Ausente o `null` se convierte explícitamente a EUR en el
adaptador web; `eur` se normaliza a `EUR`. La respuesta siempre incluye la moneda original.

Sin conversión:

```json
{"value":99.99,"currency":"EUR"}
```

Con `?date=2024-04-15&currency=USD`:

```json
{
  "value": 106.74,
  "currency": "USD",
  "originalValue": 99.99,
  "originalCurrency": "EUR",
  "exchangeRate": 1.0675,
  "exchangeRateDate": "2024-04-15"
}
```

El historial añade `currency` en cada elemento, conserva la moneda persistida y nunca se convierte.

## Semántica histórica

La fecha solicitada localiza el precio y se envía sin cambios al proveedor. El adaptador exige que
`response.date` sea igual a la fecha solicitada. Una fecha diferente se considera respuesta inválida
y activa el fallback; no se inventa una fecha ni se buscan días anteriores. La fecha confirmada se
devuelve como `exchangeRateDate`.

## Proveedor

- Principal:
  `https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@{date}/v1/currencies/{base}.json`.
- Fallback oficial:
  `https://{date}.currency-api.pages.dev/v1/currencies/{base}.json`.
- Conexión: 2 segundos por defecto.
- Lectura: 3 segundos por defecto.
- Headers: `Accept: application/json` y un `User-Agent` identificable.

El JSON observado tiene `date` y un objeto dinámico con la base en minúsculas; dentro se extrae solo
el target solicitado. Se validan presencia, tipo numérico y tasa positiva.

Timeout, red, 5xx, 404 histórico, JSON inválido, fecha distinta, base/target ausente o tasa no
positiva activan un único intento al fallback. Otros 4xx no se repiten. No hay retry adicional,
circuit breaker ni bucle de fallback. Si no hay tasa válida, la aplicación lanza
`ExchangeRateUnavailableException` y el adaptador web devuelve `503 SERVICE_UNAVAILABLE` con mensaje
genérico.

Las URLs y timeouts se externalizan mediante:
`EXCHANGE_API_PRIMARY_URL`, `EXCHANGE_API_FALLBACK_URL`,
`EXCHANGE_API_CONNECT_TIMEOUT` y `EXCHANGE_API_READ_TIMEOUT`.

## Precisión

Todo el flujo usa `BigDecimal`. Se calcula `originalValue * exchangeRate` conservando la precisión de
la tasa y se redondea únicamente el resultado final a escala 2 con `HALF_UP`. Para mantener el
challenge homogéneo, JPY también usa dos decimales.

## Tests

- Dominio: enum, cinco monedas, null, parsing, reconstrucción y solapamiento entre divisas.
- Aplicación: identidad sin proveedor, EUR/USD, USD/EUR, precisión, redondeo, misma fecha y errores.
- HTTP externo: servidor JDK local para principal, fallback, timeout, 4xx/5xx, JSON, fecha, headers y
  ausencia de retries.
- WebMvc: EUR por defecto, normalización, contratos simple/convertido y errores 400/503.
- PostgreSQL: persistencia/reconstrucción, historial, query vigente, constraints y exclusión entre
  monedas.
- E2E: Spring Boot en puerto aleatorio, PostgreSQL/Flyway reales y proveedor fake, sin Internet.

## Validación manual

El flujo se valida con `docker compose up -d --build postgres app`, health, Swagger, creación legacy,
creación USD, lectura original, identidad, conversión, CAD y persistencia tras reinicio. Los tests
automatizados no llaman a Internet.

## Benchmark

El escenario original permanece inalterado: setup funcional, 1.000 creaciones con 100 VUs, 20.000
consultas de precio con 500 VUs y 15.000 historiales con 500 VUs, en fases secuenciales. Las
peticiones omiten moneda y verifican EUR. No existe fase de conversión ni dependencia externa.
`docs/performance-results.md` conserva la línea base de `master` y no se modifica en esta rama.

## Limitaciones

- El proveedor gratuito no ofrece SLA.
- No hay caché, circuit breaker ni proveedor empresarial.
- La conversión depende de que exista el dato histórico exacto.
- No se convierte el historial ni se persisten importes convertidos.
- No hay criptomonedas, metales ni catálogo dinámico.
- JPY conserva dos decimales por simplificación.

## Futuras mejoras

Más monedas requerirían una nueva constante y migración de constraint. Fuera del alcance quedan una
caché de tasas, proveedor con SLA, circuit breaker, reglas de decimales por moneda y conversión de
historial.
