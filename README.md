# EventoMax — ms-eventomax-audit (EMX-69)

> Estado: candidato de integración. No existe todavía evidencia de despliegue AWS ni de una ejecución extremo a extremo con el productor real. El consumidor Kafka inicia deshabilitado.

Servicio Java 25 / Spring Boot 4.1.1. Recibe eventos de producciones desde Kafka, conserva un timeline idempotente en PostgreSQL y publica una **API REST de solo lectura**. La persistencia usa Flyway; no hay mocks ni datos de prueba en el runtime.

## Arquitectura y responsabilidades

- Consulta protegida: Angular/MSAL → Entra ID → API Gateway (JWT/CORS) → BFF → Audit → PostgreSQL.
- Ingesta asíncrona: Productions (Transactional Outbox) → Kafka `productions.events` → Audit → PostgreSQL. La ingesta **no** pasa por API Gateway.
- Audit exige JWT firmado, issuer/audience, `SCOPE_access_as_user` y rol `Admin` o `Auditor`; solo `GET /api/audit/timeline` está autorizado. `/actuator/health` se expone sin JWT **solo en la red privada**.
- La infraestructura aprobada prevé AWS EC2 + Docker, PostgreSQL en Amazon RDS y Kafka en EC2. No se asume MSK ni Aurora.

## REST

`GET /api/audit/timeline?actor=...&type=...&from=...&to=...&page=0&size=50`

Devuelve un **array JSON** de objetos `eventId`, `actor`, `type`, `timestamp` y `details`. El último campo es una **cadena con JSON serializado** del payload Kafka, no un objeto JSON anidado. Consultas de solo lectura para Admin y Auditor.

- Filtros opcionales `actor` y `type` por igualdad exacta y combinados con AND; `from`/`to` inclusivos.
- `timestamp`, `from` y `to`: fecha-hora ISO local **sin offset**, cuyo valor se interpreta como **UTC** por convenio interno. La conversión a la zona del navegador corresponde al frontend. No cambiar el formato sin coordinar BFF/Angular.
- Paginación: `page=0`, `size=50` por defecto; `page >= 0` y `1 <= size <= 200`. Orden descendente por `timestamp` y `id`. Página vacía: `[]`; no incluye total de páginas.
- Respuestas esperadas: 200 con array; 400 con parámetros inválidos; 401 sin JWT válido; 403 cuando falta rol/scope o se intenta escritura.

Consultar [CONTRACTS.md](CONTRACTS.md) para la versión precisa de REST y Kafka.

## Kafka

El contrato v1 consumido por el PR #4 utiliza `KafkaEventEnvelope` con `schemaVersion=1.0`, UUID `eventId`, tipo `ProductionCreated` / `ProductionStatusChanged`, timestamps UTC (`Instant`), `actor` y `payload`. Audit registra los eventos una sola vez mediante `processed_event` y la restricción única `audit_event.event_id`.

**No** habilitar `AUDIT_KAFKA_ENABLED=true` hasta validar el publicador real de Productions (EMX-92), la identidad del `actor`, tópicos de retry/DLT, seguridad de brokers y conectividad de la topología final. Las pruebas de Testcontainers usan ejemplos de mensajes, no un despliegue conjunto entre servicios.

El **Caso EventoMax también enumera el tópico `audit.timeline`**; este servicio no lo publica actualmente. La decisión de implementarlo o documentar una excepción debe cerrarse con el equipo y la pauta vigente. No declarar cumplida esa parte del caso por tener el endpoint REST. Ver [CONTRACTS.md](CONTRACTS.md).

## Desarrollo y pruebas

1. Completar las variables no secretas y privadas a partir de `.env.example` **sin versionar `.env`**. El Compose de desarrollo publica Audit en `127.0.0.1:8084` y PostgreSQL en `127.0.0.1:5435`.
2. Ejecutar `docker compose up -d --build`. Sin la activación expresa de Kafka, el listener no arranca; las consultas devuelven únicamente registros reales presentes en la base.
3. Ejecutar `./mvnw -B -ntp clean verify` (PowerShell: `.\mvnw.cmd -B -ntp clean verify`). Requiere Docker para Testcontainers Kafka/PostgreSQL.
4. Smoke test: construir `docker build -t eventomax-audit:smoke .` y ejecutar `pwsh ./scripts/Smoke-Local.ps1 -Image eventomax-audit:smoke`. Crea y elimina recursos temporales; valida health, 401, usuario no-root y Flyway V1–V3.

El workflow de GitHub Actions ejecuta Maven, Docker build, smoke test y conserva reportes Surefire. La ejecución del PR #4 sobre el commit `9976768` aprobó 28 pruebas; **cualquier commit posterior requiere nuevo CI**. Las pruebas usan datos y firmas efímeras únicamente dentro de `src/test`.

## Preparación cloud

Usar [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) como checklist y runbook. La aplicación solo podrá declararse **desplegada y demostrada** tras validar red privada, Amazon RDS, Kafka EC2, Entra ID real, BFF, API Gateway y Angular, con datos originados realmente en Productions.

Registro de seguridad: [TRUST_BOUNDARY.md](TRUST_BOUNDARY.md). Evidencia histórica del endurecimiento inicial: [docs/EMX-69-correcciones.md](docs/EMX-69-correcciones.md).
