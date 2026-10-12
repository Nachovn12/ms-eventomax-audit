# Contratos Audit — REST y Kafka v1 (EMX-69 / EMX-86 / EMX-91)

> Código implementado en la rama del PR #4; desplegado en AWS: **no verificado**. La integración local entre microservicios reales (Productions EMX-92 → Outbox → Kafka → Audit → BFF) fue comprobada exitosamente para la producción ID 2.

## REST de lectura

`GET /api/audit/timeline`

**Autorización**: Bearer JWT de Entra ID, issuer y audience aprobados, scope `access_as_user` y rol `Admin` o `Auditor`. Otros métodos y rutas no están habilitados.

| Parámetro | Contrato |
|---|---|
| `actor` | Opcional; igualdad exacta |
| `type` | Opcional; igualdad exacta |
| `from` / `to` | Opcionales; `ISO_LOCAL_DATE_TIME`, inclusivos; se interpretan como UTC, sin offset en el texto |
| `page` | Entero >= 0; por defecto 0 |
| `size` | Entero de 1 a 200; por defecto 50 |

Respuesta 200: **array JSON**, no objeto `Page`: `eventId: string`, `actor: string`, `type: string`, `timestamp: string (ISO local sin offset que representa UTC)`, `details: string|null` (texto JSON del payload), `traceId: string|null` (trazabilidad distribuida extremo a extremo), `correlationId: string|null` (correlación de transacción). Orden `timestamp DESC, id DESC`; filtros combinados con AND. Página sin registros: `[]`. Eventos históricos sin tracing devuelven `null` en ambos identificadores.

Estados HTTP: 200 correcto; 400 fecha/rango/paginación inválidos; 401 sin JWT válido; 403 sin rol/scope permitido o para métodos de escritura. Un 200 con `[]` **no prueba** que el consumidor esté recibiendo eventos.

**Compatibilidad temporal:** se mantiene el contrato de fechas sin offset heredado. En Kafka el tiempo es un `Instant` UTC; Audit lo guarda como `TIMESTAMP WITHOUT TIME ZONE` normalizado a UTC y devuelve `LocalDateTime`. Angular/BFF deben interpretarlo explícitamente como UTC. No asumir hora local del navegador ni agregar `Z` al endpoint sin coordinar un cambio de versión.

### Verificación de integración local de contratos

La prueba local de integración real confirmó la correspondencia exacta de contratos entre microservicios:
- **Evento publicado y consumido:** `ProductionCreated`
- **Producción originada:** ID `2` (HTTP 201 en Productions con JWT Productor legítimo).
- **Outbox:** Estado `SENT` verificado en base de datos de Productions.
- **Tópico Kafka:** `productions.events` (mensaje entregado a Audit).
- **PostgreSQL Audit:** Ingesta y persistencia en `audit_event` y `processed_event`, conservando el payload en `details` como texto JSON (la deduplicación idempotente se verifica en tests unitarios/Testcontainers).
- **REST Audit:** `GET /api/audit/timeline?type=ProductionCreated&page=0&size=200` → HTTP 200 con JWT Admin real, localizando el evento originado por la producción ID 2 mediante su `eventId` dentro del array JSON, con sus campos `traceId` y `correlationId` preservados en el payload de respuesta.
- **BFF Router:** `GET /api/audit/timeline?type=ProductionCreated&page=0&size=200` vía BFF → HTTP 200 con JWT Admin real; `HEAD` bloqueado con HTTP 403 (`denyAll()`); petición sin token rechazada con HTTP 401 (la propagación de cabeceras HTTP de traza se evaluó independientemente en la suite del BFF).

Identificadores de trazabilidad preservados en el JSON de respuesta:
- `eventId`: `3935d0f1-0c3c-4bf3-b7a9-901a6942a7eb`
- `traceId`: `emx69-productor-trace-20261012`
- `correlationId`: `emx69-productor-corr-20261012`

*Límites de alcance:* Esta evidencia corresponde a un entorno local multicontenedor. No implica despliegue cloud en AWS (RDS, Kafka EC2, API Gateway ni frontend Angular). La prueba negativa con token Productor en vivo en Audit sigue pendiente (validada en tests MockMvc del BFF).

## Entrada Kafka: `productions.events`

Envelope JSON versión 1.0:

| Campo | Tipo / validación |
|---|---|
| `schemaVersion` | Obligatorio, literal `"1.0"`; no se completa automáticamente |
| `eventId` | UUID obligatorio; clave de idempotencia |
| `type` | `ProductionCreated` o `ProductionStatusChanged` |
| `timestamp` | Fecha-hora ISO-8601 válida en UTC, deserializada como `Instant` |
| `actor` | String no vacío; Audit lo registra tal como lo envía Productions, no puede acreditar por sí mismo su autenticidad |
| `traceId`, `correlationId` | Opcionales; pueden ser nulos |
| `payload` | Objeto JSON no vacío, con campos obligatorios según `type` |

`ProductionCreated`: `productionId` entero positivo <= `Long.MAX_VALUE`, `productionName` no vacío, `status=SOLICITADO`, `occurredAt` fecha-hora ISO UTC válida. `organizerId`, `scheduledAt` y `location` forman parte de los ejemplos de EMX-92; confirmar obligatoriedad con el contrato del productor publicado.

`ProductionStatusChanged`: `productionId` entero positivo <= `Long.MAX_VALUE`, `previousStatus` y `newStatus` pertenecen al dominio `SOLICITADO`, `CONFIRMADO`, `EN_MONTAJE`, `EN_EJECUCION`, `CERRADO`, `CANCELADO`; `occurredAt` es ISO UTC válido. Audit no duplica la máquina completa de transiciones de Productions: registra eventos ya generados por el servicio propietario.

Eventos inválidos se rechazan y se dirigen a la DLT según la configuración Spring Kafka. Los fallos transitorios se reintentan; al persistir, el mismo `eventId` no duplica registros incluso con entregas concurrentes. `details` conserva el contenido real de `payload` como cadena JSON.

### Operación y tópicos

- Tópico de entrada: `productions.events`.
- Según el contrato de retry actual, los tópicos observados en pruebas son `productions.events-audit-retry` y `productions.events-audit-dlt`. Confirmar los nombres, particiones y políticas en el broker cloud antes de activar. Autocreación deshabilitada en producción.
- `AUDIT_KAFKA_ENABLED=false` por defecto. En pruebas locales de integración se activó expresamente para validar la ingesta desde el publicador real de Productions (producción ID 2, eventId `3935d0f1-0c3c-4bf3-b7a9-901a6942a7eb`). Para despliegues cloud, se mantendrá en `false` hasta verificar conectividad con los brokers privados de EC2 y tópicos de retry/DLT.
- DLT: mantener evento y `eventId` original; registrar causa, tópico/partición/offset y corregir antes de replay controlado. No borrar ni reiniciar offsets de producción automáticamente.

### Discrepancia con el Caso semestral

El **Caso 8 EventoMax** también define el tópico Kafka `audit.timeline` (historial quién/qué/cuándo, 3 particiones, 3 réplicas, compact/delete y retención de 14–30 días). La infraestructura de desarrollo crea ese tópico, pero **este microservicio actualmente no publica mensajes en `audit.timeline`**: su mecanismo de consulta implementado es el endpoint REST y la base PostgreSQL. Es una **brecha de alineación de la arquitectura semestral pendiente de decisión explícita**, no una funcionalidad implementada ni probada. No añadir publicación ni datos falsos sin aprobar su contrato, propietario y semántica.

Referencias: [README.md](README.md), [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md).
