package dev.lumjahaj.subscription.hub.notification.domain;

public record Attachment(String filename, String contentType, byte[] bytes) {
}
