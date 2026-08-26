package dev.lumjahaj.subscription.hub.common.api;

/**
 * Generic 409 Conflict exception for "this already exists" cases across
 * any module — avoids a bespoke XxxAlreadyExistsException + matching
 * @ExceptionHandler for every entity, since the shape is always the same:
 * a resource type, an identifier, and a 409.
 */
public class ResourceAlreadyExistsException extends RuntimeException {

    private final String code;

    public ResourceAlreadyExistsException(String resourceType, String identifier) {
        super(resourceType + " already exists: " + identifier);
        this.code = resourceType.toUpperCase() + "_ALREADY_EXISTS";
    }

    public String getCode() {
        return code;
    }
}