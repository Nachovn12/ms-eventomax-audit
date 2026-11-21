package cl.duoc.eventomax.audit.controller;

import cl.duoc.eventomax.audit.service.VerifiedActorAliasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/audit/actor-aliases")
@Tag(name = "Audit Actor Aliases", description = "Registro verificado de equivalencia propia (issuer, audience, sub) -> tid:oid")
public class ActorAliasController {

    private final VerifiedActorAliasService aliasService;

    public ActorAliasController(VerifiedActorAliasService aliasService) {
        this.aliasService = aliasService;
    }

    @PostMapping("/me")
    @Operation(
            summary = "Registrar equivalencia verificada propia",
            description = "Registra de forma idempotente la equivalencia (issuer, audience, sub) -> tid:oid derivada exclusivamente del JWT validado. No admite cuerpo de solicitud."
    )
    @ApiResponse(responseCode = "204", description = "Asociación registrada o confirmada")
    @ApiResponse(responseCode = "400", description = "Cuerpo de solicitud no permitido")
    @ApiResponse(responseCode = "401", description = "JWT ausente o inválido")
    @ApiResponse(responseCode = "403", description = "Sin permisos o claims tid/oid inválidos")
    @ApiResponse(responseCode = "409", description = "Asociación contradictoria para el subject autenticado")
    public ResponseEntity<Void> registerOwnAlias(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) throws IOException {
        if (request != null && request.getInputStream() != null && request.getInputStream().read() != -1) {
            throw new IllegalArgumentException(
                    "Este endpoint no admite cuerpo de solicitud; la identidad se obtiene exclusivamente del JWT validado");
        }
        aliasService.registerFromValidatedJwt(jwt);
        return ResponseEntity.noContent().build();
    }
}
