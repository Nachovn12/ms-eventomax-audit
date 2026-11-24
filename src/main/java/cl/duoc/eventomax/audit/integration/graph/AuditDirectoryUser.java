package cl.duoc.eventomax.audit.integration.graph;

public record AuditDirectoryUser(
        String oid,
        String displayName,
        String email
) {
}
