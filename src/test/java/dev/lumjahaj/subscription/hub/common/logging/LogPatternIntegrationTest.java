package dev.lumjahaj.subscription.hub.common.logging;

import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console pattern has to read the MDC keys the code actually writes.
 * It read %X{tenant} while TenantResolverFilter and the jobs wrote
 * MdcKeys.TENANT_ID ("tenantId"), so every log line carried an empty tenant,
 * and nothing noticed: a pattern naming a missing key prints an empty string
 * rather than failing.
 *
 * An integration test rather than a unit test so the pattern is the one
 * application.yml configures, and it writes through MdcKeys, the constants
 * every real writer uses - the two only meet at runtime. It logs its own line
 * rather than relying on a request to produce one: Spring logs a resolved
 * exception only when devtools is on, which is why an earlier version of this
 * test captured nothing at all.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogPatternIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LogPatternIntegrationTest.class);

    @Test
    void logLines_carryTheTenantAndTheRequestIdFromTheMdc(CapturedOutput output) {
        MDC.put(MdcKeys.TENANT_ID, "demo");
        MDC.put(MdcKeys.REQUEST_ID, "log-pattern-check");
        try {
            log.info("checking the console pattern");
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
            MDC.remove(MdcKeys.REQUEST_ID);
        }

        assertThat(output.getOut())
                .contains("checking the console pattern tenant=demo requestId=log-pattern-check");
    }
}
