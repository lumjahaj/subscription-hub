package dev.lumjahaj.subscription.hub.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.Slice;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Encodes the architecture described in CLAUDE.md §3 and §5 as executable rules.
 * Pure bytecode analysis: no Spring context, no containers, so it runs in
 * milliseconds alongside the unit tests.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "dev.lumjahaj.subscription.hub";

    private static final List<String> FEATURE_MODULES =
            List.of("auth", "tenancy", "catalog", "customer", "subscription", "usage", "billing", "payment",
                    "dunning", "notification", "platform");

    // Layer patterns are anchored to BASE so a third-party package that happens
    // to contain ".api." or ".infra." can never match as a dependency target.
    private static final String DOMAIN = BASE + "..domain..";
    private static final String API = BASE + "..api..";
    private static final String INFRA = BASE + "..infra..";

    // CLAUDE.md §3: "catalog, customer, and subscription have no separate
    // domain model — their entities are still just data moving DTO <-> database".
    // That deliberate choice means ports and mappers do, and must, see the
    // *Entity in ..infra.jpa.. directly. The carve-out is scoped to exactly
    // that: anything else in infra (JPA repositories, storage/pdf adapters)
    // is still forbidden to domain and api alike.
    private static final DescribedPredicate<JavaClass> JPA_ENTITY =
            resideInAPackage("..infra.jpa..").and(simpleNameEndingWith("Entity"))
                    .as("JPA entities");
    private static final DescribedPredicate<JavaClass> INFRA_EXCEPT_ENTITIES =
            resideInAPackage(INFRA).and(DescribedPredicate.not(JPA_ENTITY))
                    .as("reside in an infra package and are not JPA entities");

    // ---- 1. Layering: api -> app -> domain <- infra ----

    @ArchTest
    static final ArchRule domain_does_not_depend_on_infra =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat(INFRA_EXCEPT_ENTITIES)
                    .because("the domain holds the ports infra adapts; if the port names an adapter type "
                            + "other than the entity it deliberately has no domain model for (§3), "
                            + "persistence can no longer be swapped or reasoned about without JPA");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_api =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAPackage(API)
                    .because("the domain must survive a change of transport; HTTP DTOs and controllers "
                            + "are the outermost layer and change for reasons the domain should not care about");

    @ArchTest
    static final ArchRule api_does_not_depend_on_infra =
            noClasses().that().resideInAPackage(API)
                    .should().dependOnClassesThat(INFRA_EXCEPT_ENTITIES)
                    .because("the HTTP contract should be shaped by use cases, not by table layout; "
                            + "a controller or mapper that sees anything but the entity itself "
                            + "(which §3 uses in place of a domain model) couples response shape to the schema");

    // ---- 2. common is a leaf ----

    @ArchTest
    static final ArchRule common_does_not_depend_on_feature_modules =
            noClasses().that().resideInAPackage(BASE + ".common..")
                    .should().dependOnClassesThat().resideInAnyPackage(featurePackages())
                    .because("every feature module depends on common, so any edge back from common "
                            + "creates a cycle and makes the shared kernel impossible to reuse on its own");

    // ---- 3. Repository trio ----

    @ArchTest
    static final ArchRule spring_data_repositories_stay_in_infra_jpa =
            noClasses().that().resideOutsideOfPackage("..infra.jpa..")
                    .should().dependOnClassesThat()
                    .areAssignableTo(org.springframework.data.repository.Repository.class)
                    .because("the trio exists so JpaRepository's query-naming rules and large surface "
                            + "never leak into the domain contract; only the Impl adapter may see them");

    // ---- 4. Domain purity ----

    @ArchTest
    static final ArchRule domain_does_not_depend_on_jpa =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                    .because("a domain type reasons about the business concept independent of persistence; "
                            + "that is the only thing that earns it a place outside the entity");

    // ---- 5. Vendor containment ----

    @ArchTest
    static final ArchRule pdf_rendering_library_stays_in_billing_infra_pdf =
            noClasses().that().resideOutsideOfPackage(BASE + ".billing.infra.pdf..")
                    .should().dependOnClassesThat().resideInAPackage("com.openhtmltopdf..")
                    .because("openhtmltopdf is a rendering detail, not a view layer; confining it to one "
                            + "adapter keeps the renderer replaceable and stops anything else coupling to it");

    @ArchTest
    static final ArchRule thymeleaf_stays_in_the_two_template_adapters =
            noClasses().that().resideOutsideOfPackage(BASE + ".billing.infra.pdf..")
                    .and().resideOutsideOfPackage(BASE + ".notification.infra.template..")
                    .should().dependOnClassesThat().resideInAPackage("org.thymeleaf..")
                    .because("Thymeleaf is a rendering detail, not a view layer, used by two independent "
                            + "renderers (invoice PDFs, notification emails); confining it to their adapter "
                            + "packages keeps both replaceable and stops anything else coupling to it");

    @ArchTest
    static final ArchRule aws_sdk_stays_in_billing_infra_storage =
            noClasses().that().resideOutsideOfPackage(BASE + ".billing.infra.storage..")
                    .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk.services.s3..")
                    .because("the object-store vendor is meant to be a config value, which only holds "
                            + "while a single adapter knows the SDK exists");

    @ArchTest
    static final ArchRule sqs_stays_in_notification_infra_sqs =
            noClasses().that().resideOutsideOfPackage(BASE + ".notification.infra.sqs..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "software.amazon.awssdk.services.sqs..", "io.awspring.cloud..")
                    .because("the queue is meant to be a config value (ElasticMQ locally, real SQS in "
                            + "production, only the endpoint differs) - the same rule the S3 adapter follows");

    @ArchTest
    static final ArchRule mail_library_stays_in_notification_infra_mail =
            noClasses().that().resideOutsideOfPackage(BASE + ".notification.infra.mail..")
                    .should().dependOnClassesThat().resideInAnyPackage("jakarta.mail..", "org.springframework.mail..")
                    .because("SMTP is a config value too (Mailpit locally, Amazon SES's SMTP interface in "
                            + "production, only the host differs); one adapter is what keeps that true");

    @ArchTest
    static final ArchRule stripe_sdk_stays_in_payment_infra_gateway =
            noClasses().that().resideOutsideOfPackage(BASE + ".payment.infra.gateway..")
                    .should().dependOnClassesThat().resideInAPackage("com.stripe..")
                    .because("the payment provider is meant to be a config value, which only holds "
                            + "while one adapter knows Stripe exists - the same rule the AWS SDK follows");

    // ---- 6. Naming and placement ----

    @ArchTest
    static final ArchRule controllers_live_in_api =
            classes().that().haveSimpleNameEndingWith("Controller")
                    .should().resideInAPackage("..api..")
                    .because("the api layer is where HTTP concerns are looked for; a controller elsewhere "
                            + "escapes the layering rules and is easy to miss when reviewing the surface");

    @ArchTest
    static final ArchRule repository_impls_live_in_infra =
            classes().that().haveSimpleNameEndingWith("RepositoryImpl")
                    .should().resideInAPackage("..infra..")
                    .because("an Impl is the adapter half of the trio, and adapters are infrastructure by definition");

    @ArchTest
    static final ArchRule entities_live_in_infra_jpa =
            classes().that().haveSimpleNameEndingWith("Entity")
                    .should().resideInAPackage("..infra.jpa..")
                    .because("entities are the persistence mapping; keeping them beside their Spring Data "
                            + "repositories keeps JPA's footprint in one package per module");

    // ---- 7. No cycles between feature modules ----

    @ArchTest
    static final ArchRule feature_modules_are_free_of_cycles =
            slices().matching(BASE + ".(*)..")
                    .that(featureModule())
                    .should().beFreeOfCycles()
                    .because("a modular monolith is only splittable while dependencies between modules "
                            + "form a DAG; a cycle fuses two modules into one that merely lives in two folders");

    // ---- 8. Constructor injection ----

    @ArchTest
    static final ArchRule no_field_injection =
            noFields().should().beAnnotatedWith(Autowired.class)
                    .because("field injection hides dependencies from the constructor, allows half-built "
                            + "objects, and makes plain unit tests without a Spring context impossible");

    private static String[] featurePackages() {
        return FEATURE_MODULES.stream().map(m -> BASE + "." + m + "..").toArray(String[]::new);
    }

    private static DescribedPredicate<Slice> featureModule() {
        return DescribedPredicate.describe("are feature modules",
                slice -> FEATURE_MODULES.contains(slice.getNamePart(1)));
    }
}
