package dev.lumjahaj.subscription.hub.notification.domain;

import java.util.Map;

/**
 * Turns a template plus a view model into the subject and both bodies.
 *
 * The model must include a {@code "subject"} entry: the template's
 * {@code <title>} displays it for HTML clients, and the renderer returns it
 * unchanged from the model rather than parsing it back out of the rendered
 * markup, since the caller already knows exactly what it wrote.
 */
public interface EmailRenderer {

    EmailContent render(EmailTemplate template, Map<String, Object> model);
}
