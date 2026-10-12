# Contratos Audit — REST y Kafka v1 (EMX-69 / EMX-86 / EMX-91)

> Código implementado en la rama del PR #4; desplegado en AWS: **no verificado**. El formato v1 del consumidor se ha probado con JSON de referencia de EMX-92, pero aún falta una ejecución entre dos microservicios reales.

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

Respuesta 200: **array JSON**, no objeto `Page`: `eventId: string`, `actor: string`, `type: string`, `timestamp: string (ISO local sin offset que representa UTC)`, `details: string|null` (texto JSON del payload). Orden `timestamp DESC, id DESC`; filtros combinados con AND. Página sin registros: `[]`.

Estados HTTP: 200 correcto; 400 fecha/rango/paginación inválidos; 401 sin JWT válido; 403 sin rol/scope permitido o para métodos de escritura. Un 200 con `[]` **no prueba** que el consumidor esté recibiendo eventos.

**Compatibilidad temporal:** se mantiene el contrato de fechas sin offset heredado. En Kafka el tiempo es un `Instant` UTC; Audit lo guarda como `TIMESTAMP WITHOUT TIME ZONE` normalizado a UTC y devuelve `LocalDateTime`. Angular/BFF deben interpretarlo explícitamente como UTC. No asumir hora local del navegador ni agregar `Z` al endpoint sin coordinar un cambio de versión.

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
- `AUDIT_KAFKA_ENABLED=false` por defecto. Se activa solo después de aprobar contrato real EMX-91/92, seguridad de transporte, provisionamiento y prueba E2E.
- DLT: mantener evento y `eventId` original; registrar causa, tópico/partición/offset y corregir antes de replay controlado. No borrar ni reiniciar offsets de producción automáticamente.

### Discrepancia con el Caso semestral

El **Caso 8 EventoMax** también define el tópico Kafka `audit.timeline` (historial quién/qué/cuándo, 3 particiones, 3 réplicas, compact/delete y retención de 14–30 días). La infraestructura de desarrollo crea ese tópico, pero **este microservicio actualmente no publica mensajes en `audit.timeline`**: su mecanismo de consulta implementado es el endpoint REST y la base PostgreSQL. Es una **brecha de alineación de la arquitectura semestral pendiente de decisión explícita**, no una funcionalidad implementada ni probada. No añadir publicación ni datos falsos sin aprobar su contrato, propietario y semántica.

Referencias: [README.md](README.md), [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md).
