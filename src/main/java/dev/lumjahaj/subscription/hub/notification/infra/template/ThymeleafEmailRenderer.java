package dev.lumjahaj.subscription.hub.notification.infra.template;

import dev.lumjahaj.subscription.hub.notification.domain.EmailContent;
import dev.lumjahaj.subscription.hub.notification.domain.EmailRenderer;
import dev.lumjahaj.subscription.hub.notification.domain.EmailTemplate;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;
import java.util.Set;

/**
 * Renders both bodies of an email from one call: {@code *.html} in HTML
 * mode and {@code *.txt} in TEXT mode, sharing one {@code layout.html}
 * fragment for the header/footer chrome the HTML body needs.
 *
 * Owns a private {@link TemplateEngine}, the same as
 * {@code InvoicePdfRenderer} - nothing else touches Thymeleaf, so there is
 * no container-wide bean to accidentally couple to.
 */
@Component
public class ThymeleafEmailRenderer implements EmailRenderer {

    private final TemplateEngine templateEngine;

    public ThymeleafEmailRenderer() {
        // Two resolvers on one engine, distinguished by resolvable pattern
        // rather than by suffix: process() is called with the extension
        // already on the template name (e.g. "invoice-issued.html"), so a
        // fragment reference inside a template ("layout.html :: layout(...)")
        // resolves through the same mechanism instead of a second lookup path.
        ClassLoaderTemplateResolver html = new ClassLoaderTemplateResolver();
        html.setPrefix("templates/email/");
        html.setTemplateMode(TemplateMode.HTML);
        html.setCharacterEncoding("UTF-8");
        html.setResolvablePatterns(Set.of("*.html"));
        html.setOrder(1);
        html.setCacheable(true);

        ClassLoaderTemplateResolver text = new ClassLoaderTemplateResolver();
        text.setPrefix("templates/email/");
        text.setTemplateMode(TemplateMode.TEXT);
        text.setCharacterEncoding("UTF-8");
        text.setResolvablePatterns(Set.of("*.txt"));
        text.setOrder(2);
        text.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.addTemplateResolver(html);
        engine.addTemplateResolver(text);
        this.templateEngine = engine;
    }

    @Override
    public EmailContent render(EmailTemplate template, Map<String, Object> model) {
        Object subject = model.get("subject");
        if (!(subject instanceof String subjectText) || subjectText.isBlank()) {
            // A caller mistake, not a template-authoring one: every model
            // must carry its own subject rather than have one parsed back
            // out of the rendered <title>.
            throw new IllegalArgumentException(
                    "Email model for " + template + " is missing a \"subject\" entry");
        }

        Context context = new Context();
        context.setVariables(model);

        String html = templateEngine.process(template.templateName() + ".html", context);
        String text = templateEngine.process(template.templateName() + ".txt", context);
        return new EmailContent(subjectText, html, text);
    }
}
