# Security & Trust Boundary (EMX-122)

## Flujo de Seguridad y Autenticación

En el contexto actual de la arquitectura integrada (Frontend Angular $\rightarrow$ Microsoft Entra ID $\rightarrow$ AWS API Gateway $\rightarrow$ ms-eventomax-bff $\rightarrow$ ms-eventomax-audit):

1. **Ausencia de JWT Directo**: El microservicio `ms-eventomax-audit` **NO** recibe el JWT directamente desde el Gateway/BFF (no se ha configurado un OAuth2 Resource Server interno por diseño de red perimetral).
2. **Trust Boundary**: La validación del token JWT, la expiración, la firma (Entra ID) y el control de acceso (RBAC) ocurren en la capa del **API Gateway y el BFF**. El perímetro de seguridad termina allí.
3. **Red Interna**: Este microservicio (`ms-eventomax-audit`) opera exclusivamente dentro de la red privada interna (VPC/Cluster). 
4. **Roles**: La aplicación consumidora (BFF) asegurará que solo los roles `Admin` y `Auditor` puedan acceder al endpoint `GET /api/audit/timeline`. 
5. **No Escritura**: El rol `Auditor` solo tiene permisos de lectura. Dado que esta API es 100% *read-only* (no existen métodos POST/PUT/DELETE), este microservicio es seguro por diseño contra escrituras maliciosas.

## Restricciones
- No se exponen endpoints de manera pública (ni con `permitAll`).
- Toda llamada HTTP hacia `ms-eventomax-audit` debe provenir del BFF a través de la red privada.
- No se han inventado headers arbitrarios para inyectar identidad; la seguridad depende de la arquitectura de la infraestructura y del gateway.
