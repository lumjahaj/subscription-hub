package dev.lumjahaj.subscription.hub.auth.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.auth.app.AuthService;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The only endpoint that is reachable without a token, and the only one
// that takes a tenant from the caller. 200 rather than 201: issuing a
// token doesn't create a server-side resource - nothing is stored, and
// there is no URL that would identify it.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * The tenant is set here, around the call, rather than inside AuthService.
     *
     * <p>app_user is under row-level security, whose predicate reads a setting
     * bound to the connection when a transaction opens. issueToken is itself
     * the @Transactional boundary, so setting the tenant inside it would come
     * too late and the lookup would match nothing — every login would fail
     * with correct credentials, which is the same shape of failure @TenantId
     * produced on this table before it was excluded from it.
     *
     * <p>This does not trust the caller's tenant any further than the lookup
     * already did. AuthService scopes by exactly this value either way, so the
     * setting only makes the database agree with the WHERE clause; naming
     * someone else's tenant still means having no account in it. What the
     * token then asserts is read back off the row that was found.
     */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(TenantContext.callAs(request.tenantId(), () -> authService.issueToken(request)));
    }
}
