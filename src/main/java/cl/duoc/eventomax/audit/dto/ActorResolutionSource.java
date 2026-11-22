package cl.duoc.eventomax.audit.dto;

public enum ActorResolutionSource {
    SYSTEM,
    EVENT,
    ENTRA_DIRECTORY,
    VERIFIED_SUB_TO_ENTRA_DIRECTORY,
    LEGACY,
    UNRESOLVED
}
