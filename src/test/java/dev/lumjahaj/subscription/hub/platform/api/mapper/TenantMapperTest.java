package dev.lumjahaj.subscription.hub.platform.api.mapper;

import dev.lumjahaj.subscription.hub.platform.api.dto.TenantCreateRequest;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantProvisionedResponse;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantResponse;
import dev.lumjahaj.subscription.hub.platform.app.ProvisionedTenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantMapperTest extends MapperValidationSupport {

    @Test
    void toResponse_mapsAllFieldsFromTenant() {
        TenantResponse response = TenantMapper.toResponse(new Tenant("globex", "Globex Corp", false));

        assertThat(response).isEqualTo(new TenantResponse("globex", "Globex Corp", false));
    }

    @Test
    void toResponse_carriesTheInitialPasswordOnlyOnProvisioning() {
        ProvisionedTenant provisioned = new ProvisionedTenant(
                new Tenant("globex", "Globex Corp", true), "admin@globex.test", "generated-secret");

        TenantProvisionedResponse response = TenantMapper.toResponse(provisioned);

        assertThat(response.tenant()).isEqualTo(new TenantResponse("globex", "Globex Corp", true));
        assertThat(response.adminEmail()).isEqualTo("admin@globex.test");
        assertThat(response.initialPassword()).isEqualTo("generated-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"globex", "globex-eu", "g2", "a-very-long-but-still-valid-tenant-slug-of-sixty-three-chars-xx"})
    void request_acceptsValidSlugs(String id) {
        assertThat(VALIDATOR.validate(request(id))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Globex",          // uppercase: "Acme" and "acme" must not be two tenants
            "__no_tenant__",   // the resolver's sentinel must never be a real tenant
            "globex_eu",       // underscores excluded, which is what rules the sentinel out
            "1globex",         // must start with a letter
            "g",               // too short
            "globex eu",
            "globex/eu",
            "a-very-long-and-no-longer-valid-tenant-slug-of-sixty-four-char-x"
    })
    void request_rejectsInvalidSlugs(String id) {
        assertThat(VALIDATOR.validate(request(id))).isNotEmpty();
    }

    @Test
    void request_rejectsAnInvalidAdminEmail() {
        assertThat(VALIDATOR.validate(new TenantCreateRequest("globex", "Globex Corp", "not-an-email"))).isNotEmpty();
    }

    private static TenantCreateRequest request(String id) {
        return new TenantCreateRequest(id, "Globex Corp", "admin@globex.test");
    }
}
