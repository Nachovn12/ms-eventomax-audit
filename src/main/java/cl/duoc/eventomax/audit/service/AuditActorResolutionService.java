package cl.duoc.eventomax.audit.service;

import cl.duoc.eventomax.audit.dto.ActorResolutionSource;
import cl.duoc.eventomax.audit.integration.graph.AuditActorDirectoryProvider;
import cl.duoc.eventomax.audit.integration.graph.AuditDirectoryUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resuelve la identidad visible de los actores presentes en una página autorizada de Auditoría
 * siguiendo la prioridad estricta:
 * 1. SYSTEM
 * 2. EVENT (actorDisplayName persistido en el evento)
 * 3. ENTRA_DIRECTORY (actor canónico tid:oid resuelto vía Microsoft Graph)
 * 4. VERIFIED_SUB_TO_ENTRA_DIRECTORY (sub con asociación verificada en BD + Microsoft Graph)
 * 5. LEGACY (alias legible no técnico)
 * 6. UNRESOLVED
 */
@Service
public class AuditActorResolutionService {

    private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern CANONICAL_TID_OID_PATTERN = Pattern.compile("^" + UUID_REGEX + ":" + UUID_REGEX + "$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^" + UUID_REGEX + "$");
    private static final Pattern OPAQUE_SUB_PATTERN =
            Pattern.compile("^(?:(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[A-Za-z0-9_-]{22,}|[A-Za-z0-9_-]{32,})$");

    public record ResolvedActor(
            String displayName,
            String email,
            ActorResolutionSource source
    ) {}

    public record ResolutionContext(
            Map<String, String> verifiedSubToCanonical,
            Map<String, Optional<AuditDirectoryUser>> directoryByCanonical
    ) {}

    private final VerifiedActorAliasService aliasService;
    private final AuditActorDirectoryProvider directoryProvider;

    @Autowired
    public AuditActorResolutionService(
            @Autowired(required = false) VerifiedActorAliasService aliasService,
            @Autowired(required = false) AuditActorDirectoryProvider directoryProvider) {
        this.aliasService = aliasService;
        this.directoryProvider = directoryProvider;
    }

    public ResolutionContext prepareContext(Collection<ActorLookupCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ResolutionContext(Collections.emptyMap(), Collections.emptyMap());
        }

        Set<String> canonicalIds = new HashSet<>();
        Set<String> subjectsToLookup = new HashSet<>();

        for (ActorLookupCandidate candidate : candidates) {
            String rawActor = candidate.actor() != null ? candidate.actor().trim() : "";
            if (rawActor.isEmpty() || "system".equalsIgnoreCase(rawActor)) {
                continue;
            }
            if (hasText(candidate.eventDisplayName())) {
                continue;
            }
            if (isCanonicalActorId(rawActor)) {
                canonicalIds.add(rawActor);
            } else {
                subjectsToLookup.add(rawActor);
            }
        }

        Map<String, String> verifiedAliases = (aliasService != null && !subjectsToLookup.isEmpty())
                ? aliasService.findCanonicalActorIdsBySubjects(subjectsToLookup)
                : Collections.emptyMap();

        canonicalIds.addAll(verifiedAliases.values());

        Map<String, Optional<AuditDirectoryUser>> directoryByCanonical = new HashMap<>();
        if (directoryProvider != null) {
            for (String canonicalId : canonicalIds) {
                directoryByCanonical.put(canonicalId, directoryProvider.findUserByCanonicalId(canonicalId));
            }
        }

        return new ResolutionContext(verifiedAliases, directoryByCanonical);
    }

    public ResolvedActor resolve(String actor, String eventDisplayName, ResolutionContext context) {
        String rawActor = actor != null ? actor.trim() : "";

        // 1. SYSTEM
        if ("system".equalsIgnoreCase(rawActor)) {
            return new ResolvedActor(null, null, ActorResolutionSource.SYSTEM);
        }

        // 2. EVENT
        String normalizedEventName = AuditService.normalizeActorDisplayName(eventDisplayName);
        if (normalizedEventName != null) {
            return new ResolvedActor(normalizedEventName, null, ActorResolutionSource.EVENT);
        }

        if (rawActor.isEmpty()) {
            return new ResolvedActor(null, null, ActorResolutionSource.UNRESOLVED);
        }

        // 3. ENTRA_DIRECTORY (canonical tid:oid)
        if (isCanonicalActorId(rawActor)) {
            Optional<AuditDirectoryUser> dirUser = context.directoryByCanonical().getOrDefault(rawActor, Optional.empty());
            if (dirUser.isPresent()) {
                AuditDirectoryUser user = dirUser.get();
                String resolvedName = hasText(user.displayName()) ? user.displayName() : user.email();
                return new ResolvedActor(resolvedName, user.email(), ActorResolutionSource.ENTRA_DIRECTORY);
            }
            return new ResolvedActor(null, null, ActorResolutionSource.UNRESOLVED);
        }

        // 4. VERIFIED_SUB_TO_ENTRA_DIRECTORY
        String verifiedCanonical = context.verifiedSubToCanonical().get(rawActor);
        if (verifiedCanonical != null) {
            Optional<AuditDirectoryUser> dirUser = context.directoryByCanonical().getOrDefault(verifiedCanonical, Optional.empty());
            if (dirUser.isPresent()) {
                AuditDirectoryUser user = dirUser.get();
                String resolvedName = hasText(user.displayName()) ? user.displayName() : user.email();
                return new ResolvedActor(resolvedName, user.email(), ActorResolutionSource.VERIFIED_SUB_TO_ENTRA_DIRECTORY);
            }
            return new ResolvedActor(null, null, ActorResolutionSource.UNRESOLVED);
        }

        // 5. LEGACY (readable non-technical actor)
        if (isReadableLegacy(rawActor)) {
            return new ResolvedActor(null, null, ActorResolutionSource.LEGACY);
        }

        // 6. UNRESOLVED
        return new ResolvedActor(null, null, ActorResolutionSource.UNRESOLVED);
    }

    public record ActorLookupCandidate(String actor, String eventDisplayName) {}

    public static boolean isCanonicalActorId(String value) {
        return value != null && CANONICAL_TID_OID_PATTERN.matcher(value.trim()).matches();
    }

    public static boolean isOpaqueSub(String value) {
        return value != null && OPAQUE_SUB_PATTERN.matcher(value.trim()).matches();
    }

    public static boolean isReadableLegacy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !CANONICAL_TID_OID_PATTERN.matcher(trimmed).matches()
                && !UUID_PATTERN.matcher(trimmed).matches()
                && !OPAQUE_SUB_PATTERN.matcher(trimmed).matches();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
