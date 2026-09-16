package dev.lumjahaj.subscription.hub.auth.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenResponse;
import dev.lumjahaj.subscription.hub.auth.app.PlatformAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// A separate endpoint rather than an optional tenantId on /api/auth/token:
// "leave out the tenant to become a platform admin" is exactly the kind of
// rule a client, or a later refactor, gets wrong. 200 for the same reason
// AuthController returns 200 - nothing is created.
@RestController
@RequestMapping("/api/platform/auth")
public class PlatformAuthController {

    private final PlatformAuthService platformAuthService;

    public PlatformAuthController(PlatformAuthService platformAuthService) {
        this.platformAuthService = platformAuthService;
    }

    @PostMapping("/token")
    public ResponseEntity<PlatformTokenResponse> token(@Valid @RequestBody PlatformTokenRequest request) {
        return ResponseEntity.ok(platformAuthService.issueToken(request));
    }
}
