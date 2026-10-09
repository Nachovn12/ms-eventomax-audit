# Contratos Audit — EMX-86

## REST implementado

GET /api/audit/timeline, array de:
`eventId: string, actor: string, type: string, timestamp: ISO local datetime, details: string|null`.

Filtros opcionales actor/type por igualdad exacta; from/to inclusivos.
Combinación AND. Fechas sin offset según contrato provisional existente.
Paginación: page=0, size=50; page >= 0; 1 <= size <= 200.
Orden timestamp DESC, id DESC. Página fuera del rango: [].
400 para rango invertido, fecha o paginación inválidas.
401 sin JWT válido; 403 sin scope/rol o para escritura.

La respuesta sigue siendo un array: la paginación cambia el límite por defecto,
no agrega un envelope. Clientes deben solicitar páginas sucesivas.

## Kafka: pendiente EMX-71

ProductionEventMessage NO es un contrato aprobado de Productions.
El listener está desactivado por defecto. Sus campos actuales son una fixture provisional
para verificar transporte, retry/DLT y persistencia en pruebas aisladas.

Antes de habilitar:
1. Referenciar commit/versión del contrato de EMX-71.
2. Acordar eventId, tipos, actor real, instante/zona horaria y contexto de producción.
3. Mapear trazabilidad solo cuando exista en el evento real; no inferir actor desde organizerId.
4. Validar esquema y ejemplo JSON compartido desde el productor.
5. Aprovisionar tópicos de retry/DLT y demostrar replay idempotente.

No se publica audit.timeline. La API es el mecanismo de consulta.
