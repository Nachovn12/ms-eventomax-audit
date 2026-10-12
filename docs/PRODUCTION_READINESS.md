# EventoMax Audit — runbook y gate de despliegue (EMX-69)

> Estado actual: **no desplegado ni validado en AWS**. Validación local exitosa de integración Productions → Outbox → Kafka → Audit → BFF. Código candidato en el PR #4; la incorporación a `develop` y las verificaciones cloud son pasos posteriores. No confundir la validación local con un despliegue en producción.

## 1. Evidencia disponible y criterio de salida

| Aspecto | Evidencia / gate |
|---|---|
| Seguridad backend | JWT Entra con issuer/audience/exp/scope/roles probado en CI; validación local exitosa con JWT real de Admin; prueba en vivo de Productor 403 pendiente en Audit |
| Persistencia | PostgreSQL 17 Testcontainers, idempotencia concurrente, rollback, Flyway V1–V4 aplicada y verificada con PostgreSQL real |
| Kafka v1 | Ingesta real local comprobada con Productions (EMX-92, producción ID 2, eventId `3935d0f1-0c3c-4bf3-b7a9-901a6942a7eb`); 32 pruebas aprobadas en CI |
| Artefacto | Docker multi-stage Java 25, proceso non-root, smoke test en CI (`eventomax-audit:smoke`) |
| REST | `GET /api/audit/timeline` validado localmente con JWT Admin real (HTTP 200) y filtros |
| BFF, Gateway, Angular | BFF integrado localmente con Audit REST (Admin 200, HEAD 403, sin token 401). Recorrido cloud completo Angular → API Gateway pendiente |
| Tópico `audit.timeline` del Caso | **Pendiente de decisión de implementación o excepción acordada**, no publicado por Audit |

El resultado CI verificado para `ca35b7d4015b2adcda8e19066f4e01a5cf19f4f1`: **32 pruebas aprobadas** (incluyendo trazabilidad distribuida y migración Flyway V4), Docker build y smoke test (`Smoke-Local.ps1`). En `ms-eventomax-bff` (PR #18), el commit `2f32b034c4947a4ca373c1b58603f3d325c975eb` reportó CI SUCCESS con 201 pruebas aprobadas. Ver el historial de GitHub Actions.

### 1.1. Evidencia real de integración local (Octubre 2026)

Se ejecutó la prueba local de integración real con el flujo completo:
1. **Productions con JWT Productor**: `HTTP 201 Created` (`productionId: 2`, rol `Productor`).
2. **Transactional Outbox**: Evento registrado y marcado como `SENT`.
3. **Kafka (`productions.events`)**: Publicación real del evento `ProductionCreated`.
4. **Audit PostgreSQL**: Ingesta y persistencia en `audit_event` y `processed_event`, conservando `details`, `trace_id` y `correlation_id` (la deduplicación idempotente se verifica en la suite automatizada con Testcontainers).
5. **Audit REST con JWT Admin**: `GET /api/audit/timeline?type=ProductionCreated&page=0&size=200` → `HTTP 200 OK`, localizando el evento originado por la producción ID 2 mediante su `eventId` dentro del array JSON, con sus campos `traceId` y `correlationId` preservados en el payload de respuesta.
6. **BFF → Audit REST con JWT Admin**: `GET /api/audit/timeline?type=ProductionCreated&page=0&size=200` vía BFF → `HTTP 200 OK`, recuperando el mismo registro JSON (la propagación de cabeceras HTTP `X-Trace-Id` y `X-Correlation-Id` se verificó por separado en el BFF).
7. **BFF HEAD Audit con Admin**: `HEAD /api/audit/timeline` → `HTTP 403 Forbidden` (`denyAll()` activo).
8. **BFF GET Audit sin JWT**: `GET /api/audit/timeline` → `HTTP 401 Unauthorized`.

Identificadores de trazabilidad verificados en el JSON de respuesta:
- **EventId:** `3935d0f1-0c3c-4bf3-b7a9-901a6942a7eb`
- **TraceId:** `emx69-productor-trace-20261012`
- **CorrelationId:** `emx69-productor-corr-20261012`

**Límites estrictos de la evidencia:**
- La integración comprobada es estrictamente **LOCAL**.
- No existe todavía evidencia del recorrido cloud completo Angular → API Gateway → BFF → Audit.
- `AUDIT_KAFKA_ENABLED=false` sigue siendo el valor por defecto; en la prueba local se habilitó expresamente para la captura.
- La prueba HTTP 403 de GET Audit con JWT Productor real en vivo en el puerto 8084 sigue pendiente (validada en tests de integración del BFF).
- La publicación en `audit.timeline` sigue pendiente de decisión contractual; no atribuir cumplimiento automático.
- No se afirma que RDS, Kafka en EC2 o API Gateway se encuentren desplegados ni operativos para esta prueba.

## 2. Contratos y coordinación previos

- Asegurar que los PR #3/#4 estén integrados en `develop` tras revisiones y CI; nunca desplegar desde un PR Draft.
- Verificar el contrato real publicado de EMX-91/EMX-92: envelope v1, eventos, actor del JWT de Productions, timestamps UTC, claves y estados. Los fixtures de Testcontainers no sustituyen una ejecución cross-service.
- Acordar cómo representar en Angular las fechas UTC serializadas por REST sin offset. Mantener compatibilidad con la API existente hasta aprobación de cambio.
- Resolver la diferencia del Caso sobre `audit.timeline` y registrar su decisión arquitectónica, sin asignar cumplimiento por inferencia.

## 3. Variables y secretos (solo en runtime, nunca en Git)

| Variable | Uso / condición |
|---|---|
| `DB_URL` | JDBC de la base lógica Audit en **Amazon RDS PostgreSQL**, con política TLS aprobada |
| `DB_USER`, `DB_PASSWORD` | Usuario con permisos limitados; almacenar credenciales en Secrets Manager/Parameter Store o mecanismo aprobado |
| `ENTRA_ISSUER_URI` | Debe coincidir exactamente con `iss` de los access tokens reales (tenant Entra v2.0) |
| `ENTRA_AUDIENCE` | Debe coincidir con el `aud` real del access token y configuración de API Gateway/BFF |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker(s) Kafka **privados** en EC2; nunca `localhost` en diferentes hosts |
| `AUDIT_KAFKA_ENABLED` | Por defecto `false`; en pruebas locales se activó para validar ingesta real. En cloud activar a `true` solo tras validar conectividad privada con broker EC2 |
| `AUDIT_KAFKA_TOPIC`, `AUDIT_KAFKA_GROUP_ID` | Por defecto `productions.events`, `audit-group`; personalizar solo con contrato aprobado |
| `SPRING_PROFILES_ACTIVE` | `prod`; desactiva OpenAPI/Swagger y restringe Actuator a `health` |

Para transporte TLS/SASL Kafka usar propiedades `SPRING_KAFKA_*`/archivos de configuración aprobados y protección de secretos sin versionarlos. No habilitar SASL o MSK por suposición: el stack acordado es Kafka en EC2. Configurar TLS/verificación de servidor de RDS según certificados y red aprobados.

## 4. Infraestructura y topología de red

- EC2 Apps ejecuta BFF y Audit con `docker-compose.prod.yml`, red externa privada `eventomax-net` y sin mapeos de puerto públicos para Audit. Solo BFF debe alcanzar Audit mediante reglas de red/segmentación verificables; la red Docker compartida por sí sola **no** prueba ese aislamiento.
- Security Groups mínimos: BFF ↔ Audit en red privada; Audit → RDS PostgreSQL/5432; Audit → Kafka EC2 en listener privado. Limitar el tráfico desde Internet a los endpoints aprobados de Gateway/front.
- Para EC2 Kafka, `advertised.listeners` debe anunciar DNS/IP **privada accesible** desde EC2 Apps. El valor `localhost:9092` del Compose local de infraestructura **no sirve entre instancias**.
- El Caso contempla Kafka/ZooKeeper 3 brokers, 3 particiones y factor 3 para producción. La infraestructura `infra-eventomax` actual de desarrollo usa un broker y factor 1; preparar el plan de topología final sin simular que está desplegado.
- Con autocreación de tópicos desactivada, provisionar `productions.events`, los tópicos efectivos `-audit-retry` / `-audit-dlt` y sus particiones/réplicas/retención; comprobar a través del broker y de logs Spring Kafka.

## 5. Migraciones, respaldo y lanzamiento

1. Antes de Flyway, respaldar RDS (snapshot/backup aprobado). Si existiera una base con histórico, revisar duplicados `event_id` antes de aplicar V2, sin borrar registros: `SELECT event_id, count(*) FROM audit_event GROUP BY event_id HAVING count(*) > 1;`.
2. No alterar V1/V2/V3/V4 ya aplicadas; asegurar que Flyway aplica las cuatro en una base nueva y validar después con `flyway_schema_history`. Hibernate usa `ddl-auto=validate`.
3. Construir desde commit de `develop` aprobado, con digest de imagen identificado, y desplegar mediante Compose sin credenciales versionadas. La política `restart: always` reinicia por terminación, pero **no** por `unhealthy` exclusivamente.
4. Validar contenedor no-root, `/actuator/health` dentro de la red privada, logs, conexión real a RDS y Flyway. Con Kafka inactivo el healthcheck HTTP **no demuestra** consumo ni conectividad con Kafka.
5. Una vez validado el broker, tópicos, seguridad y productor real, activar `AUDIT_KAFKA_ENABLED=true` de manera controlada. Verificar ingestión, deduplicación, offsets, retries y DLT antes de habilitar tráfico del frontend.

**Rollback:** desplegar imagen previa y revisar migraciones compatibles hacia atrás. Flyway no revierte automáticamente DDL; restaurar un snapshot de RDS solo con un plan de recuperación aprobado y consideración de pérdida de datos. No ejecutar `docker compose down -v` ni eliminar volúmenes/datos productivos.

## 6. Prueba E2E para EP2 (sin mocks en runtime)

1. Iniciar sesión en Angular con MSAL/OIDC Authorization Code + PKCE mediante un usuario Auditor/Admin real; confirmar `aud`, `iss`, `scp` y `roles` del access token sin publicar ni registrar el token.
2. Invocar `GET /api/audit/timeline` por API Gateway → BFF → Audit con rol permitido: 200 y JSON correcto. Verificar 401 sin token/firma inválida y 403 sin rol/scope. Validar CORS desde el dominio real del frontend.
3. Crear una producción mediante su endpoint protegido; cambiar su estado usando los seis valores del dominio cuando corresponda. Confirmar publicación del Outbox al broker real, consumo y persistencia en Audit con el mismo `eventId`.
4. Consultar desde Angular `/audit` los eventos recién creados; verificar filtros usuario/fecha/tipo, paginación, orden y que no haya duplicados; corroborar datos en RDS sin sembrar mocks.
5. Registrar evidencia: SHA, imagen/digest, run CI, URLs internas sin secretos, captura de la ruta y métricas de mensajes/DLT, resultado verificable y responsables. No adjudicar puntajes de rúbrica sin demostración.

## 7. Operación, seguridad y recuperación DLT

- Logs en stdout sin secretos ni JWT; correlacionar `eventId` y trazas cuando estén disponibles. Vigilar conectividad a RDS, lag del consumidor, retries, tamaño/retención DLT, reinicios y estado health.
- Los mensajes inválidos persisten en DLT (según broker y retención). Registrar tópico/partición/offset, cabeceras/categoría de error, diagnosticar, corregir causa y hacer **replay controlado conservando `eventId`**; la idempotencia impedirá duplicar auditorías.
- `@DltHandler` no procesa DLT automáticamente. Disponer de mecanismo/documentación operativa autorizada para inspección y replay; no limpiar tópicos ni reiniciar offsets sin autorización.
- Actualizar versión, plan de recuperación, responsable y evidencias al desplegar. Actualmente sigue siendo preparación, no despliegue probado.

Fuentes de referencia: [CONTRACTS.md](../CONTRACTS.md), [TRUST_BOUNDARY.md](../TRUST_BOUNDARY.md), rúbrica EP2 y Caso 8 EventoMax.
