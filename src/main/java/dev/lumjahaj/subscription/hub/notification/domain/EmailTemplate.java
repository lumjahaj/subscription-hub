package dev.lumjahaj.subscription.hub.notification.domain;

/**
 * Maps to {@code templates/email/<name>.html} and {@code <name>.txt} -
 * every email has both an HTML rendering and a plain-text alternative,
 * rendered together by {@link EmailRenderer}.
 */
public enum EmailTemplate {
    INVOICE_ISSUED("invoice-issued"),
    PAYMENT_FAILED("payment-failed"),
    PAYMENT_RECOVERED("payment-recovered"),
    SUBSCRIPTION_CANCELED("subscription-canceled");

    private final String templateName;

    EmailTemplate(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() {
        return templateName;
    }
}
