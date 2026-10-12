# EventoMax Audit — EMX-69

Java 25 / Spring Boot 4.1.1. API de lectura sobre PostgreSQL, alimentada por Kafka.
Flujo obligatorio: Angular → Entra ID → API Gateway → BFF → Audit.

## Estado de integración

Esta corrección endurece Audit, pero **no cierra EMX-69**:
- EMX-71 debe aprobar el contrato real de `productions.events`.
- EMX-72 debe incorporar routing BFF y validar propagación del Bearer.
- AWS, Entra real y demo E2E todavía requieren evidencia.
- El DTO Kafka sigue siendo provisional: `AUDIT_KAFKA_ENABLED=false` por defecto.
  Las pruebas de transporte usan un tópico aislado y no acreditan compatibilidad con Productions.

## API

`GET /api/audit/timeline?actor=...&type=...&from=...&to=...&page=0&size=50`

Respuesta: array JSON, conservando la forma anterior. Filtros exactos actor/type, combinados
con AND; fechas inclusivas. Orden por timestamp descendente y luego ID descendente.
page >= 0; size entre 1 y 200. Sin parámetros: primera página de 50.
Rango invertido, fecha inválida y paginación inválida responden 400.
El contrato temporal usa fecha ISO local sin offset; la normalización UTC se acordará en EMX-71.
Ver CONTRACTS.md.

## Seguridad

JWT firmado por el issuer aprobado, audiencia correcta, exp obligatorio, scope
`access_as_user` y rol `Admin` o `Auditor`. Solo lectura.
Health es accesible sin JWT en la red interna; el resto de rutas no aprobadas se deniega.
Swagger/OpenAPI requiere los mismos permisos. Nunca exponer Audit directamente en Internet.
Ver TRUST_BOUNDARY.md.

## Configuración y ejecución

Copiar `.env.example` a `.env` y completar DB_USER, DB_PASSWORD, ENTRA_ISSUER_URI y
ENTRA_AUDIENCE con valores aprobados. No versionar credenciales.
`docker compose up -d --build` levanta PostgreSQL y Audit local.
Los puertos de desarrollo solo se enlazan a 127.0.0.1: API 8084 y PostgreSQL 5435.
En Docker, localhost no identifica el host ni otro contenedor. Ajustar
KAFKA_BOOTSTRAP_SERVERS y los advertised listeners del broker cuando corresponda.

Producción: `docker compose -f docker-compose.prod.yml up -d --build`, únicamente
con autorización de despliegue. Requiere DB_URL y red externa eventomax-net.
El Compose productivo no publica puertos. La red compartida no garantiza por sí sola
que únicamente el BFF pueda conectar.

## Persistencia y migraciones

Flyway habilitado y Hibernate `ddl-auto=validate`.
V1 permanece intacta; V2 agrega UNIQUE a audit_event.event_id.
Antes de aplicar V2 en una base existente, comprobar:
```sql
SELECT event_id, count(*) FROM audit_event GROUP BY event_id HAVING count(*) > 1;
```
Si hay duplicados, resolver su conservación con el equipo antes de migrar. La migración
falla sin borrar información; no hace limpieza automática.
Una inserción `ON CONFLICT DO NOTHING` reclama eventId en processed_event. La reserva
y el timeline pertenecen a la misma transacción; un fallo revierte ambos.

## Kafka provisional: solo habilitar tras cerrar EMX-71

Se incluye el starter Kafka de Spring Boot 4 para registrar realmente los listeners.
JSON sin cabeceras de clase se interpreta con el DTO local; se ignoran los tipos remotos.
ErrorHandlingDeserializer conserva bytes ilegibles para DLT.
Retry utiliza serialización JSON/byte[] y tres intentos totales (inicial + dos reintentos).
Sufijos exclusivos de Audit: `-audit-retry`, `-audit-dlt`.
La autocreación de tópicos está desactivada en producción. Infraestructura debe
provisionar principal, retry y DLT con particiones compatibles antes de habilitar el listener.
Confirmar nombres efectivos con la versión de Spring Kafka usada y la configuración final.

El consumidor automático DLT permanece detenido para no avanzar offsets sin intervención.
Revisar con un grupo de inspección separado, registrar tópico/partición/offset y causa,
corregir el problema y acordar un replay manteniendo eventId. No borrar DLT ni reiniciar
offsets productivos automáticamente. Retención y monitoreo deben acordarse con infraestructura.

## Pruebas reproducibles

Requisitos: Java 25, Docker operativo y acceso inicial a Maven Central/Docker Hub.
```sh
./mvnw -B -ntp verify
```
Windows: `.\\mvnw.cmd -B -ntp verify`.
PostgreSQL 17 efímero por Testcontainers, Flyway real y Kafka embebido.
Se prueban concurrencia, rollback, filtros, JWT firmado con claves efímeras,
reintentos, DLT y JSON inválido. Ninguna prueba requiere credenciales cloud.
Reportes: target/surefire-reports.
CI ejecuta verify, construye Docker y ejecuta scripts/Smoke-Local.ps1; conserva reportes.
Para repetir el smoke local, construir la imagen eventomax-audit:emx69-review y ejecutar
`pwsh ./scripts/Smoke-Local.ps1`. Usa una red y PostgreSQL efímeros; los elimina al terminar.
Dockerfile omite pruebas porque Docker-in-Docker no está disponible en la etapa de build:
la prueba es un gate previo obligatorio, no una garantía del build de imagen por sí solo.

Matriz y evidencia: docs/EMX-69-correcciones.md.
