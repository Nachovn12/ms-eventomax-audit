# Seguridad y trust boundary — EMX-122

## Implementado en Audit

Resource Server JWT con issuer, firma, audiencia, expiración obligatoria y validación
temporal estándar. GET timeline exige scope access_as_user y rol Admin o Auditor.
No acepta identidad mediante headers arbitrarios.
Todas las operaciones de escritura se deniegan. Health es público solo dentro del
perímetro de red; Swagger/OpenAPI requiere los permisos de lectura.

ENTRA_ISSUER_URI y ENTRA_AUDIENCE son obligatorios. No hay modo permitAll ni credenciales
inventadas para iniciar la aplicación. La consulta del issuer es diferida para que health
no dependa de una llamada a Entra al arrancar.

## Contrato propuesto para EMX-72

API Gateway valida JWT; BFF vuelve a validar y autorizar, y reenvía el mismo Bearer a Audit.
Esto es coherente con el DomainRoutingClient inspeccionado en BFF develop
b82142c7d815f36c979a620dcb2c795042fe7aca, que propaga Authorization para sus rutas existentes.
La ruta Audit todavía no existe en ese HEAD: su integración no se considera probada.

## Perímetro de red

Compose productivo no publica puertos. Infraestructura debe restringir entrada al servicio
desde el BFF. eventomax-net es una red compartida y no acredita exclusividad del llamador.
JWT tampoco prueba que una petición atravesó API Gateway/BFF: la restricción de red
es necesaria para respetar el flujo oficial. Los puertos de desarrollo solo usan loopback.

## Evidencia pendiente antes de cierre

- Routing Audit en BFF y propagación Bearer verificadas.
- 200 Admin/Auditor; 401 sin token, expirado, firma inválida, issuer/audience incorrectos.
- 403 rol no permitido o scope ausente; rechazo de escritura.
- Prueba del acceso directo bloqueado por red y flujo cloud completo.
- Configuración Entra/API Gateway y valores de issuer/audience aprobados.

Las pruebas locales usan firmas RSA y un decoder de prueba con la misma política de
validación. No equivalen a una integración con el tenant real.
