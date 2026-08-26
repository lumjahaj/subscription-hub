package dev.lumjahaj.subscription.hub.common.api;

/**
 * Generic 404 Not Found exception, same reasoning as
 * ResourceAlreadyExistsException — one class, reused everywhere a lookup
 * by tenant + code/id fails.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(resourceType + " not found: " + identifier);
        this.code = resourceType.toUpperCase() + "_NOT_FOUND";
    }

    public String getCode() {
        return code;
    }
}