package dev.lumjahaj.subscription.hub.audit.app;

import dev.lumjahaj.subscription.hub.audit.domain.ActorType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditActor;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditActorsTest {

    @Test
    void noAuthentication_isSystem() {
        assertThat(AuditActors.from(null)).isEqualTo(AuditActor.SYSTEM);
    }

    @Test
    void anonymousToken_isSystem() {
        // What a permitAll path such as the Stripe webhook carries.
        var anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(AuditActors.from(anonymous)).isEqualTo(AuditActor.SYSTEM);
    }

    @Test
    void tenantToken_isUserIdentifiedBySubject() {
        var token = tenantToken("acme", "user-1");

        assertThat(AuditActors.from(token)).isEqualTo(new AuditActor(ActorType.USER, "user-1"));
    }

    @Test
    void platformToken_isPlatformAdminIdentifiedBySubject() {
        var jwt = jwt().subject("admin-1").claim("roles", List.of("PLATFORM_ADMIN")).build();
        var token = new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_PLATFORM_ADMIN"));

        assertThat(AuditActors.from(token)).isEqualTo(new AuditActor(ActorType.PLATFORM_ADMIN, "admin-1"));
    }

    @Test
    void tokenWithNeitherShape_isRefusedRatherThanCalledSystem() {
        var token = new JwtAuthenticationToken(jwt().subject("who").build(), AuthorityUtils.NO_AUTHORITIES);

        assertThatThrownBy(() -> AuditActors.from(token)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tenantUser_cannotRecordForAnotherTenant() {
        var token = tenantToken("acme", "user-1");

        assertThatCode(() -> AuditActors.requireActorMayRecordFor(token, "acme")).doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditActors.requireActorMayRecordFor(token, "demo"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JwtAuthenticationToken tenantToken(String tenantId, String subject) {
        var jwt = jwt().subject(subject).claim("tenant_id", tenantId).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256");
    }
}
