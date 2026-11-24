package cl.duoc.eventomax.audit.integration.graph;

import java.util.Optional;

public interface AuditActorDirectoryProvider {

    Optional<AuditDirectoryUser> findUserByCanonicalId(String canonicalActorId);
}
