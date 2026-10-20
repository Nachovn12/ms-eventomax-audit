# EMX-69 — Correcciones y evidencia
Fecha: 2026-10-08 (America/Santiago).
Base revisada: develop, 03a7df5910737f9ade4670d574d09ffef7d549de.
Rama: fix/EMX-69-audit-hardening.

## Resultado local
- Baseline original: 5 tests, 0 fallos/errores/omisiones.
- Corrección: Maven verify, 16 tests, 0 fallos/errores/omisiones; BUILD SUCCESS.
- Ejecución completa terminada el 8 de octubre de 2026 a las 19:19:50 -03:00.
- Entorno: Java 25, Spring Boot 4.1.1, Spring Kafka 4.1.1,
  PostgreSQL 17.11 en Testcontainers 2.0.5 y Kafka embebido.
- Reportes reproducibles en target/surefire-reports (no versionados).
- Imagen Docker construida localmente: eventomax-audit:emx69-review.
- scripts/Smoke-Local.ps1 pasó: healthcheck Docker healthy, timeline sin JWT=401,
  usuario no root y Flyway V1/V2 aplicadas. Contenedores y red efímeros eliminados al terminar.
- CI configurada en .github/workflows/ci.yml; su resultado remoto debe revisarse por separado.
- Sin despliegue AWS ni cambios en Jira, BFF, Productions o infraestructura compartida.

## Cambios verificables

| Tarea | Cambio | Evidencia de prueba | Límite |
|---|---|---|---|
| EMX-85 | Rama desde HEAD auditado | Historial Git y baseline ejecutado | Sin reescritura de develop |
| EMX-86 | Contrato REST preciso, paginación y contrato Kafka marcado provisional | AuditControllerTest / AuditApplicationTests | Falta contrato aprobado EMX-71 |
| EMX-87 | Reserva atómica eventId y UNIQUE mediante V2; V1 intacta | Migración real, duplicado secuencial, 8 concurrentes, rollback y redelivery | V2 falla si existen duplicados históricos; no elimina registros |
| EMX-88 | Filtros reales y paginación 0/50, máximo 200, orden timestamp/id | Consulta HTTP con datos PostgreSQL y páginas consecutivas; 400 | Array conservado; clientes deben paginar |
| EMX-89 | Starter Kafka Boot 4, tipo local explícito, ErrorHandlingDeserializer, productor JSON/bytes, retry/DLT separados | JSON sin headers; duplicado; falla DB transitoria recuperada al tercer intento; DLT semántica y bytes inválidos; siguiente evento procesado | Transporte probado con fixture provisional; listener desactivado por defecto |
| EMX-90 | Tests reales, CI, .dockerignore, Compose local en loopback y documentación | Maven verify completo, imagen construida y smoke Docker aprobado | Resultado cloud/CI remoto separado |
| EMX-122 | Resource Server, firma/issuer/audience/exp, scope access_as_user, roles Admin/Auditor, denyAll restante | Tokens RSA efímeros, 200/401/403 y escritura denegada | Falta routing BFF, tenant real y aislamiento cloud |

## Hallazgo adicional confirmado al ejecutar Kafka
El pom original solo incluía spring-kafka. Con la modularización de Spring Boot 4 faltaba
spring-boot-kafka/autoconfiguración y no se registraba el listener. La primera prueba
de transporte expuso la falta de consumo. Se reemplazó por spring-boot-starter-kafka.
Referencia: https://docs.spring.io/spring-boot/appendix/auto-configuration-classes/spring-boot-kafka.html

## Matriz EP2 (sin asignar puntaje)
Fuente: Instructivo_Presentacion_EP2_DSY1107_EventoMax.docx del proyecto.
El PDF con nombre EP2 contiene internamente una pauta EP3 de RabbitMQ; no se usa para
añadir requisitos de esa evaluación.

| Indicador | Estado relacionado con Audit |
|---|---|
| Rutas API Manager (13%) | Endpoint interno probado; routing BFF/Gateway pendiente EMX-72 |
| CORS (7%) | Fuera del MS; sin evidencia en esta corrección |
| Tenant, app, flujo usuario, PKCE (10%+10%+10%+15%) | Fuera del MS; no evaluados aquí |
| JWT en todas las rutas (20%) | Política Audit probada localmente; cadena Gateway/BFF/cloud pendiente |
| Ruta funcional y JSON esperado (15%) | JSON y filtros locales probados; E2E cloud pendiente |

## Condiciones para cerrar la historia
1. EMX-71: contrato versionado y mapping de campos reales; acordar semántica temporal.
2. Aprovisionar y verificar productions.events, productions.events-audit-retry y
   productions.events-audit-dlt (nombres observados con Spring Kafka 4.1.1 y retry actual).
3. EMX-72/122: routing BFF hacia Audit, mismo Bearer validado, valores de Entra aprobados
   y aislamiento de red que impida saltarse el flujo oficial.
4. Registrar evidencia de despliegue, health y demo autorizada.
5. Mantener EMX-69 abierto hasta completar esas dependencias; esta corrección no las
   sustituye ni atribuye su implementación al autor de Audit.
