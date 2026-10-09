# Guía de Operación y Paso a Producción (EMX-69)

## Dependencias Críticas
Este servicio NO debe ser activado para consumir tráfico productivo hasta que:
1. **EMX-91 / EMX-71**: Se defina el contrato oficial de eventos Kafka (el consumidor actualmente está inactivo).
2. **EMX-72**: El BFF implemente enrutamiento con propagación del JWT.
3. **EMX-122**: Se establezcan las fronteras de red en el clúster.

## Variables de Entorno (Sin Secretos)
Para levantar en producción, definir:
* `DB_URL`: JDBC URL (ej. jdbc:postgresql://db-audit:5432/audit_db)
* `DB_USER`: Usuario DB
* `DB_PASSWORD`: Password DB
* `KAFKA_BOOTSTRAP_SERVERS`: Servidores Kafka
* `AUDIT_KAFKA_ENABLED`: `false` por defecto. Cambiar a `true` solo tras aprobar EMX-91.
* `ENTRA_ISSUER_URI`: URI del Issuer OAuth2.
* `ENTRA_AUDIENCE`: Audiencia de la API aprobada.
* `SPRING_PROFILES_ACTIVE`: `prod` (oculta Swagger y expone solo `/actuator/health`).

## Dependencias de Infraestructura
* **PostgreSQL (17+)**: Requiere base de datos transaccional con Flyway habilitado. No usa `localhost` en el contenedor.
* **Apache Kafka**:
  * Tópico Principal: `productions.events`
  * Tópico de Reintentos: `productions.events-audit-retry`
  * Tópico DLT (Dead Letters): `productions.events-audit-dlt`
  _Nota: Como `autoCreateTopics=false`, la infraestructura debe aprovisionar estos tópicos previamente (sugerido: factor de replicación 3, al menos 3 particiones)._

## Procedimientos de Operación

### Migraciones y Respaldo
Las migraciones se ejecutan mediante Flyway (`V1`, `V2`).
**Respaldo**: Utilizar `pg_dump` directo al RDS antes de aplicar migraciones mayores. El servicio usa transacciones y `ddl-auto=validate`.

### Despliegue mediante Docker Compose
Ejecutar:
```bash
docker-compose -f docker-compose.prod.yml up -d
```
Garantiza un usuario no-root en un entorno multistage.

### Healthchecks
El contenedor docker incluye un healthcheck HTTP validando `/actuator/health`. Fallos allí provocarán reinicios (`restart: always`).

### Diagnóstico de Incidentes y Logs
Los logs se emiten en formato estándar (stdout). Las excepciones en la API (ej. parámetros inválidos) se capturan globalmente devolviendo JSON estandarizado, sin stacktraces (400, 401, 403).

### Recuperación DLT
Los eventos defectuosos van al tópico `*-audit-dlt`. No se pierden, quedan almacenados para recuperación asíncrona mediante herramientas externas de Kafka o un reprocesador manual en futuras iteraciones.

### Rollback
En caso de falla de despliegue, aplicar `docker-compose down` y restaurar la imagen previa. Si hay falla en BD, Flyway no realiza rollback de DDL, se debe restaurar el snapshot de PostgreSQL.

