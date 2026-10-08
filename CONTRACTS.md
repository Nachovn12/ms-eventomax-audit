# Contratos Audit (EMX-86)

## API Contract

**Endpoint:** `GET /api/audit/timeline`

**Descripción:** Retorna el timeline de eventos de auditoría (solo lectura).

**Parámetros de consulta (Filtros soportados):**
- `actor` (String): Filtra por el actor que realizó la acción.
- `from` (DateTime): Fecha y hora de inicio.
- `to` (DateTime): Fecha y hora de fin.
- `type` (String): Filtra por tipo de evento.

**Validaciones:**
- Si `from > to` -> `400 Bad Request`
- Si cualquier fecha tiene un formato inválido -> `400 Bad Request`

## Kafka Consumer Mapping (`productions.events`)

> **[BLOQUEADO]** Mapeo pendiente del contrato oficial de Kafka `productions.events` (Dependencia: EMX-71).
> No se implementarán suposiciones sobre los campos exactos (`actor`, `metadata`, `productionId`, `traceId`, `correlationId`, etc.) hasta que el productor defina el esquema.

## Estrategia `productions.events` y `audit.timeline`
- `ms-eventomax-audit` consumirá los eventos del tópico `productions.events` para alimentar su read model interno.
- No se implementará la publicación en un tópico conceptual `audit.timeline` a menos que sea explícitamente requerido por la arquitectura integrada, ya que el API `/api/audit/timeline` es el mecanismo oficial de lectura para el frontend.
