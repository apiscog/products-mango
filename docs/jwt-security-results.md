# JWT security results

## Objetivo

Esta rama anade autenticacion y autorizacion sin alterar el contrato funcional ni el benchmark
original. La comparacion enfrenta la entrega base de master con esta arquitectura:

~~~text
Cliente / k6 -> Spring Security + JWT -> Products API -> PostgreSQL
~~~

Products API actua solo como OAuth2 Resource Server. No emite tokens en produccion, no gestiona
usuarios ni contrasenas y no incorpora Gateway ni Authorization Server.

## Decisiones

- Spring Security OAuth2 Resource Server, sin estado y sin sesiones HTTP.
- JWT firmado exclusivamente con RS256; la API solo recibe la clave publica.
- Validacion de firma, exp, nbf, iss=products-challenge-dev y aud=products-api.
- El claim firmado scope se convierte mediante el soporte estandar a autoridades SCOPE_*.
- CSRF esta desactivado porque la API usa Bearer tokens, no cookies de autenticacion.
- PostgreSQL y sus invariantes siguen siendo la fuente de verdad.

| Scope | Operaciones |
|---|---|
| **products.read** | **GET /products/** |
| **products.write** | **POST /products/** |

El token writer de desarrollo contiene ambos scopes; products.write por si solo no concede lectura.
Health, OpenAPI y Swagger UI son publicos. Un JWT ausente o invalido devuelve 401; un JWT valido sin
el scope requerido devuelve 403, ambos con JSON estable y sin detalles criptograficos.

## Desarrollo local

Cada clon genera un par RSA independiente con APIs estandar de Java 21:

~~~bash
java tools/jwt/GenerateDevKeys.java
~~~

La utilidad crea:

- tools/jwt/generated/dev-private-key.pem, PKCS#8, para el emisor local;
- config/jwt/generated/dev-public-key.pem, X.509, para Products API.

Ambas rutas estan ignoradas por Git y Docker build. Compose monta solo la publica, en modo read-only;
la privada no entra en la imagen ni en el contenedor. El flujo es:

La utilidad intenta limitar la privada al propietario mediante permisos POSIX. La ausencia de soporte,
como en el proveedor de archivos habitual de Windows, solo produce un aviso.

~~~text
GenerateDevKeys.java -> privada local + publica local
GenerateToken.java   -> firma con la privada
Products API         -> valida con la publica
~~~

En PowerShell:

~~~powershell
$writerToken = java tools/jwt/GenerateToken.java writer
$readerToken = java tools/jwt/GenerateToken.java reader
~~~

En macOS o Linux:

~~~bash
writer_token="$(java tools/jwt/GenerateToken.java writer)"
reader_token="$(java tools/jwt/GenerateToken.java reader)"
~~~

Una segunda ejecucion no sobrescribe el par. Un estado incompleto falla y --force regenera ambos
archivos mediante temporales; esto invalida tokens anteriores. No es un Authorization Server.

En produccion deben externalizarse JWT_PUBLIC_KEY_LOCATION, JWT_ISSUER y JWT_AUDIENCE, usar HTTPS y
custodiar la clave privada en un proveedor de identidad. La API podria consumir una publica montada o
un JWKS del emisor; Products API nunca necesita la clave privada.

## Tests

La suite rapida usa spring-security-test para probar reglas HTTP sin criptografia artificial en cada
caso. La integracion usa JWT reales y un par RSA exclusivo de test para cubrir RS256 valido, firma
incorrecta, manipulacion, expiracion, nbf, issuer, audience, ausencia de claims, algoritmo rechazado,
scopes y Bearer mal formado. Los E2E usan HTTP real, PostgreSQLContainer y Flyway.

Resultado validado:

- 92 tests rapidos, sin Docker;
- 51 ejecuciones de integracion;
- 143 ejecuciones totales.

## Benchmark

Se mantuvo sin cambios semanticos el escenario de master. El unico cambio es la cabecera
Authorization: Bearer, con un writer token generado justo antes de cada ejecucion.

Las cifras siguientes no se repitieron al cambiar la generacion de claves. Se obtuvieron con un par
RSA local equivalente; la nueva utilidad solo cambia como se crea y distribuye ese material. RS256,
claims, scopes, validacion y coste por peticion permanecen iguales.

| Orden | Fase | Peticiones exactas | VUs |
|---:|---|---:|---:|
| 1 | Product creation | 1.000 | 100 |
| 2 | Price query (2024-04-15) | 20.000 | 500 |
| 3 | History query | 15.000 | 500 |

El setup publico de health crea un producto, tres precios, hace tres consultas por fecha y consulta el
historial. Las fases son secuenciales y usan shared-iterations.

## Entorno

- Fecha: 2026-07-20.
- Windows 10 Home 64-bit 10.0.19045, Docker Desktop engine 29.6.1, Linux x86_64.
- Recursos visibles a Docker: 12 CPU y aproximadamente 7,32 GiB.
- Limites Compose: API 1 CPU/1 GiB, PostgreSQL 0,5 CPU/1 GiB, k6 1 CPU/1 GiB.
- Imagen k6 grafana/k6:1.7.1; PostgreSQL postgres:17-alpine.

Los datos son locales, dependen del equipo y no constituyen un SLA.

## Linea base de master

Fuente: [performance-results.md](performance-results.md).

| Ejecucion | Fase | Duracion | Throughput | p50 | p95 | p99 | Max |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | Product creation | 3 s | 379,65 req/s | 208,97 ms | 498,26 ms | 692,19 ms | 998,40 ms |
| 1 | Price query | 18 s | 1.118,97 req/s | 299,40 ms | 1,10 s | 1,69 s | 4,29 s |
| 1 | History query | 16 s | 949,08 req/s | 400,62 ms | 1,12 s | 1,62 s | 2,99 s |
| 2 | Product creation | 3 s | 396,92 req/s | 205,70 ms | 498,65 ms | 699,43 ms | 1,00 s |
| 2 | Price query | 19 s | 1.081,80 req/s | 301,54 ms | 1,10 s | 1,80 s | 4,19 s |
| 2 | History query | 16 s | 914,83 req/s | 406,39 ms | 1,20 s | 1,70 s | 3,90 s |

## Ejecucion JWT 1: volumen limpio

| Fase | Requests | VUs | Duracion | Throughput | avg | p50 | p95 | p99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Product creation | 1.000 | 100 | 5 s | 208,63 req/s | 464,91 ms | 415,68 ms | 884,47 ms | 1,18 s | 1,68 s |
| Price query | 20.000 | 500 | 33 s | 625,07 req/s | 788,84 ms | 513,15 ms | 1,90 s | 2,79 s | 5,89 s |
| History query | 15.000 | 500 | 24 s | 617,69 req/s | 789,28 ms | 609,59 ms | 1,79 s | 2,49 s | 5,20 s |

Setup: 9 requests HTTP y 44/44 checks. Carga: 36.000 requests exactos, 100 % de checks, cero
estados inesperados, 5xx, contratos invalidos e iteraciones descartadas. Duracion total: 63 s.
Exit code: 0.

## Ejecucion JWT 2: volumen conservado

El setup creo un nuevo producto con ID 1002; no hubo conflictos con los datos anteriores.

| Fase | Requests | VUs | Duracion | Throughput | avg | p50 | p95 | p99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Product creation | 1.000 | 100 | 4 s | 237,70 req/s | 403,03 ms | 400,31 ms | 797,27 ms | 998,97 ms | 1,50 s |
| Price query | 20.000 | 500 | 30 s | 683,71 req/s | 715,17 ms | 500,60 ms | 1,79 s | 2,59 s | 6,26 s |
| History query | 15.000 | 500 | 22 s | 679,44 req/s | 719,85 ms | 590,49 ms | 1,59 s | 2,20 s | 4,19 s |

Setup: 9 requests HTTP y 44/44 checks. Carga: 36.000 requests exactos, 100 % de checks, cero
estados inesperados, 5xx, contratos invalidos e iteraciones descartadas. Duracion total: 57 s.
Exit code: 0.

## Comparacion

Variacion de latencia = (JWT - base) / base; variacion de throughput = (JWT - base) / base. Un
porcentaje positivo de latencia representa coste, no mejora.

| Fase | Ejecucion | Throughput | Coste p50 | Coste p95 | Coste p99 |
|---|---:|---:|---:|---:|---:|
| Product creation | 1 | -45,0 % | +98,9 % | +77,5 % | +70,5 % |
| Product creation | 2 | -40,1 % | +94,6 % | +59,9 % | +42,8 % |
| Price query | 1 | -44,1 % | +71,4 % | +72,7 % | +65,1 % |
| Price query | 2 | -36,8 % | +66,0 % | +62,7 % | +43,9 % |
| History query | 1 | -34,9 % | +52,2 % | +59,8 % | +53,7 % |
| History query | 2 | -25,7 % | +45,3 % | +32,5 % | +29,4 % |

Bajo 500 VUs y el limite de una CPU, la validacion criptografica se ejecuta en cada request y la API
permanece cerca de una CPU completa. El resultado muestra el coste de seguridad esperado; JWT no se
presenta como una optimizacion de rendimiento.

## Recursos

Muestras puntuales aproximadas:

| Ejecucion | Fase | API CPU / memoria | PostgreSQL CPU / memoria | k6 CPU / memoria |
|---:|---|---|---|---|
| 1 | Product creation | 107,49 % / 372,4 MiB | 11,61 % / 80,11 MiB | 10,76 % / 169,2 MiB |
| 1 | Price query | 106,17 % / 454,3 MiB | 19,89 % / 81,61 MiB | 28,07 % / 244,5 MiB |
| 1 | History query | 104,18 % / 481,9 MiB | 20,02 % / 82,98 MiB | 32,15 % / 247,1 MiB |
| 2 | Product creation | 104,25 % / 337,8 MiB | 10,45 % / 43,46 MiB | 18,44 % / 150,1 MiB |
| 2 | Price query | 105,98 % / 417,5 MiB | 17,04 % / 44,93 MiB | 25,58 % / 210,8 MiB |
| 2 | History query | 104,86 % / 446,9 MiB | 19,42 % / 47,58 MiB | 38,19 % / 212,8 MiB |

docker stats puede mostrar algo mas de 100 % en muestras cortas alrededor del limite de una CPU.

## Seguridad y limitaciones

- HTTPS es obligatorio en produccion para proteger el Bearer token en transito.
- Las claves productivas deben ser externas; cada par local generado solo sirve para desarrollo.
- Los tokens son cortos y nunca deben registrarse ni almacenarse en Git.
- Un JWT autofirmado no ofrece revocacion inmediata; un emisor real debe definir rotacion y revocacion.
- No existe gestion de usuarios ni emision de tokens en Products API.
- La medicion es local, corta y con 500 VUs; no es un SLA ni un analisis de capacidad universal.

## Conclusion

La rama conserva contratos, cantidades y exactitud funcional, y aplica autenticacion/autorizacion
antes del controlador. Las dos ejecuciones fueron correctas y repetibles, pero RS256 por request tuvo
un coste medible bajo el limite local de una CPU. Es un intercambio de seguridad por capacidad, no
una mejora de rendimiento, y debe dimensionarse de nuevo en el entorno real. Generar el par por clon
elimina material privado fijo del repositorio sin cambiar ese resultado.
