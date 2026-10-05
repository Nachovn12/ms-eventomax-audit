# EventoMax Audit Microservice

## Propósito
Audit será responsable posteriormente de la auditoría y trazabilidad de EventoMax.

## Estado
Baseline técnico EMX-70.

**Aclaración explícita:** La implementación funcional pertenece a la tarea EMX-69.

### Fuera de alcance de EMX-70:
- `/api/audit/*`
- timeline
- filtros
- entities
- repositories
- read model funcional
- migraciones Flyway funcionales
- Kafka consumers/listeners
- retry/DLT funcional
- lógica de auditoría
- integración BFF/API Gateway

## Stack Base
- Java 25
- Spring Boot
- JPA/Hibernate
- PostgreSQL
- Flyway
- Spring Kafka
- Actuator
- OpenAPI
- Docker / Compose

## Variables de Entorno
Copia el archivo `.env.example` a `.env` y configura tus valores locales:
- `POSTGRES_DB`
- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS`

## Comandos de Test
Para ejecutar las pruebas:
```bash
./mvnw clean test
```

## Git Flow
- `main` = estable/demo
- `develop` = integración
- `feature/EMX-69-*` = desarrollo funcional posterior

## Arquitectura FUTURA (Documentada)
Angular
→ Microsoft Entra ID
→ JWT
→ AWS API Gateway
→ ms-eventomax-bff
→ ms-eventomax-audit
→ PostgreSQL read model

**Notas adicionales de arquitectura:**
- Kafka `productions.events` alimentará posteriormente Audit.
- Angular nunca debe llamar directamente a `ms-eventomax-audit`.
