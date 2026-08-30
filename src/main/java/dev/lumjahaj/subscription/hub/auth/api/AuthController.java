package dev.lumjahaj.subscription.hub.auth.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.auth.app.AuthService;
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

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(authService.issueToken(request));
    }
}
