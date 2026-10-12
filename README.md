# EventoMax — ms-eventomax-audit (EMX-69)

> Estado: candidato de integración con validación local confirmada. Se comprobó exitosamente la integración local real Productions → Outbox → Kafka → Audit → BFF. No existe todavía evidencia de despliegue AWS. El consumidor Kafka inicia deshabilitado por defecto (`AUDIT_KAFKA_ENABLED=false`).

Servicio Java 25 / Spring Boot 4.1.1. Recibe eventos de producciones desde Kafka, conserva un timeline idempotente en PostgreSQL y publica una **API REST de solo lectura**. La persistencia usa Flyway; no hay mocks ni datos de prueba en el runtime.

## Arquitectura y responsabilidades

- Consulta protegida: Angular/MSAL → Entra ID → API Gateway (JWT/CORS) → BFF → Audit → PostgreSQL.
- Ingesta asíncrona: Productions (Transactional Outbox) → Kafka `productions.events` → Audit → PostgreSQL. La ingesta **no** pasa por API Gateway.
- Audit exige JWT firmado, issuer/audience, `SCOPE_access_as_user` y rol `Admin` o `Auditor`; solo `GET /api/audit/timeline` está autorizado. `/actuator/health` se expone sin JWT **solo en la red privada**.
- La infraestructura aprobada prevé AWS EC2 + Docker, PostgreSQL en Amazon RDS y Kafka en EC2. No se asume MSK ni Aurora.

## REST

`GET /api/audit/timeline?actor=...&type=...&from=...&to=...&page=0&size=50`

Devuelve un **array JSON** de objetos `eventId`, `actor`, `type`, `timestamp`, `details`, `traceId` y `correlationId`. Los dos últimos campos preservan la trazabilidad distribuida extremo a extremo originada en Productions (nulos en eventos históricos). El campo `details` es una **cadena con JSON serializado** del payload Kafka, no un objeto JSON anidado. Consultas de solo lectura para Admin y Auditor.

- Filtros opcionales `actor` y `type` por igualdad exacta y combinados con AND; `from`/`to` inclusivos.
- `timestamp`, `from` y `to`: fecha-hora ISO local **sin offset**, cuyo valor se interpreta como **UTC** por convenio interno. La conversión a la zona del navegador corresponde al frontend. No cambiar el formato sin coordinar BFF/Angular.
- Paginación: `page=0`, `size=50` por defecto; `page >= 0` y `1 <= size <= 200`. Orden descendente por `timestamp` y `id`. Página vacía: `[]`; no incluye total de páginas.
- Respuestas esperadas: 200 con array; 400 con parámetros inválidos; 401 sin JWT válido; 403 cuando falta rol/scope o se intenta escritura.

### Evidencia de integración local E2E

Se verificó el flujo real entre microservicios locales:
- **POST Productions con JWT Productor**: `HTTP 201 Created` (`productionId: 2`, rol `Productor`).
- **Transactional Outbox**: Evento registrado y marcado como `SENT`.
- **Kafka (`productions.events`)**: Publicación real del evento `ProductionCreated`.
- **Audit PostgreSQL**: Ingesta y persistencia exitosa en `audit_event` y `processed_event` (la garantía de idempotencia ante duplicados concurrentes se valida en las pruebas automatizadas con Testcontainers).
- **Audit REST con JWT Admin**: `GET /api/audit/timeline?type=ProductionCreated&page=0&size=200` → `HTTP 200 OK`, localizando el evento originado por la producción ID 2 mediante su `eventId` dentro del array JSON, con sus campos `traceId` y `correlationId` preservados en el payload de respuesta.
- **BFF → Audit REST con JWT Admin**: `GET /api/audit/timeline?type=ProductionCreated&page=0&size=200` a través del BFF → `HTTP 200 OK`, recuperando el mismo registro JSON (la propagación de cabeceras HTTP `X-Trace-Id` y `X-Correlation-Id` se verificó de forma separada en las pruebas del BFF).
- **BFF HEAD Audit con Admin**: `HEAD /api/audit/timeline` → `HTTP 403 Forbidden` (`denyAll()` activo).
- **BFF GET Audit sin JWT**: `GET /api/audit/timeline` → `HTTP 401 Unauthorized`.

Identificadores de trazabilidad verificados en el JSON de respuesta:
- **EventId:** `3935d0f1-0c3c-4bf3-b7a9-901a6942a7eb`
- **TraceId:** `emx69-productor-trace-20261012`
- **CorrelationId:** `emx69-productor-corr-20261012`

*Límites de la evidencia:* La verificación es de ámbito local. El recorrido cloud completo (Angular → API Gateway → BFF → Audit), la infraestructura cloud (Amazon RDS, Kafka EC2, API Gateway) y la prueba negativa en vivo de `HTTP 403` con JWT Productor en Audit continúan pendientes para la fase de homologación cloud.

Consultar [CONTRACTS.md](CONTRACTS.md) para la versión precisa de REST y Kafka.

## Kafka

El contrato v1 consumido por el PR #4 utiliza `KafkaEventEnvelope` con `schemaVersion=1.0`, UUID `eventId`, tipo `ProductionCreated` / `ProductionStatusChanged`, timestamps UTC (`Instant`), `actor`, `traceId`, `correlationId` y `payload`. Audit registra los eventos una sola vez mediante `processed_event` y la restricción única `audit_event.event_id`, persistiendo la trazabilidad distribuida en `audit_event`.

El parámetro `AUDIT_KAFKA_ENABLED=false` se mantiene por defecto por seguridad operativa. En las pruebas locales de integración se activó expresamente para validar la ingesta desde el publicador real de Productions (producción ID 2, eventId `3935d0f1-0c3c-4bf3-b7a9-901a6942a7eb`). Para despliegues cloud, se mantendrá en `false` hasta verificar conectividad con los brokers privados de EC2 y tópicos de retry/DLT.

El **Caso EventoMax también enumera el tópico `audit.timeline`**; este servicio no lo publica actualmente. La decisión de implementarlo o documentar una excepción debe cerrarse con el equipo y la pauta vigente. No declarar cumplida esa parte del caso por tener el endpoint REST. Ver [CONTRACTS.md](CONTRACTS.md).

## Desarrollo y pruebas

1. Completar las variables no secretas y privadas a partir de `.env.example` **sin versionar `.env`**. El Compose de desarrollo publica Audit en `127.0.0.1:8084` y PostgreSQL en `127.0.0.1:5435`.
2. Ejecutar `docker compose up -d --build`. Sin la activación expresa de Kafka, el listener no arranca; las consultas devuelven únicamente registros reales presentes en la base.
3. Ejecutar `./mvnw -B -ntp clean verify` (PowerShell: `.\mvnw.cmd -B -ntp clean verify`). Requiere Docker para Testcontainers Kafka/PostgreSQL.
4. Smoke test: construir `docker build -t eventomax-audit:smoke .` y ejecutar `pwsh ./scripts/Smoke-Local.ps1 -Image eventomax-audit:smoke`. Crea y elimina recursos temporales; valida health, 401, usuario no-root y Flyway V1–V4.

El workflow de GitHub Actions ejecuta Maven, Docker build, smoke test y conserva reportes Surefire:
- **Audit PR #4:** commit `ca35b7d4015b2adcda8e19066f4e01a5cf19f4f1` con estado **SUCCESS** (32 pruebas reportadas, incluyendo trazabilidad y Flyway V4).
- **BFF PR #18:** commit `2f32b034c4947a4ca373c1b58603f3d325c975eb` con estado **SUCCESS** (201 pruebas reportadas).
Las pruebas automatizadas emplean datos y firmas efímeras dentro de `src/test`.

## Preparación cloud

Usar [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) como checklist y runbook. La aplicación solo podrá declararse **desplegada y demostrada** tras validar red privada, Amazon RDS, Kafka EC2, Entra ID real, BFF, API Gateway y Angular, con datos originados realmente en Productions.

Registro de seguridad: [TRUST_BOUNDARY.md](TRUST_BOUNDARY.md). Evidencia histórica del endurecimiento inicial: [docs/EMX-69-correcciones.md](docs/EMX-69-correcciones.md).
