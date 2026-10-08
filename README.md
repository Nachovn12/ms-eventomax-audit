# EventoMax Audit Microservice (ms-eventomax-audit)

Microservicio responsable de la auditoría y trazabilidad del proyecto EventoMax, de manera *read-only*.

## API y Contratos

### Timeline (`GET /api/audit/timeline`)
El endpoint principal para consultar el timeline de auditoría es de **solo lectura**.
- **Filtros soportados:**
  - `actor` (String)
  - `from` (ISO DateTime, ej: `2026-10-08T10:00:00`)
  - `to` (ISO DateTime)
  - `type` (String)

**Respuestas:**
- `200 OK`: Lista de eventos.
- `400 Bad Request`: Si `from > to` o si el formato de fechas es inválido.

## Arquitectura y Componentes

### Flyway y PostgreSQL
Se utiliza **Flyway** para el versionamiento y la creación del esquema en PostgreSQL (`V1__init_audit_schema.sql`). La configuración de Hibernate es estricta (`ddl-auto=validate`) para prevenir alteraciones accidentales del esquema.

### Kafka y Consumo de Eventos
Consume el tópico `productions.events`.
- **Idempotencia:** Se verifica cada evento consumido cruzando su `eventId` contra la tabla `processed_event` de manera transaccional. Los eventos duplicados se descartan (no duplican el timeline).
- **Retry y DLT:** Si falla el consumo, se realizan 3 reintentos (`@RetryableTopic`). Si persisten los fallos, el evento se envía a un DLT (`@DltHandler`) para su intervención.

### Seguridad (Trust Boundary)
- El microservicio reside en la red interna y no está expuesto públicamente.
- La validación de JWT (y su pertenencia a Entra ID), así como el control de RBAC (Admin, Auditor) se gestiona en la frontera (BFF / API Gateway).
- El microservicio no asume roles de Resource Server de manera independiente al no recibir el token de forma directa. (Referencia: `TRUST_BOUNDARY.md`).

## Entorno (Variables)
No almacenes valores reales aquí (ni secretos). Configura un archivo `.env` basándote en `.env.example`:
- `POSTGRES_DB`
- `DB_URL` (jdbc:postgresql://host:port/db)
- `DB_USER`
- `DB_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS`

## Pruebas
Todos los tests (contexto, filtros, fechas, controllers y excepciones) se pueden correr vía Maven Wrapper:
```bash
./mvnw clean test
```

## Docker Compose (Local)
Para ejecutar este microservicio de manera local junto con su base de datos:
```bash
docker compose up -d --build
```
Mapeo de puertos local (por arquitectura DSY1107):
- **Audit MS:** `8084` -> interno `8080`
- **PostgreSQL:** `5435` -> interno `5432`
