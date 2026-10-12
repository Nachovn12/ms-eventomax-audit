# EventoMax Audit — runbook y gate de despliegue (EMX-69)

> Estado actual: **no desplegado ni validado en AWS**. Código candidato en el PR #4; la incorporación a `develop` y las verificaciones cloud son pasos posteriores. No confundir `BUILD SUCCESS` con un despliegue en producción.

## 1. Evidencia disponible y criterio de salida

| Aspecto | Evidencia / gate |
|---|---|
| Seguridad backend | JWT Entra con issuer/audience/exp/scope/roles probado con firmas RSA de test; falta tenant real |
| Persistencia | PostgreSQL 17 Testcontainers, idempotencia concurrente, rollback, Flyway V1–V4 |
| Kafka v1 | Kafka Testcontainers, 28 pruebas del último PR verificado en `9976768`; falta publicación real desde Productions |
| Artefacto | Docker multi-stage Java 25, proceso non-root, smoke test en CI |
| REST | `GET /api/audit/timeline` con filtros, paginación, 200/400/401/403 locales |
| API Gateway, BFF, Angular | **Pendientes** de integración E2E real y pruebas de 401/403/200 desde frontend |
| Tópico `audit.timeline` del Caso | **Pendiente de decisión de implementación o excepción acordada**, no publicado por Audit |

El resultado CI verificado para `9976768`: 28 pruebas (15 Kafka, 7 aplicación, 5 controlador y 1 Swagger), Docker build y smoke test. **Actualizar esta evidencia con el nuevo SHA/CI tras cualquier cambio.** Ver el historial del PR #4 en GitHub Actions.

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
| `AUDIT_KAFKA_ENABLED` | Inicialmente `false`; activar a `true` solo tras E2E con broker/tópicos/actor verificados |
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
