package dev.lumjahaj.subscription.hub.tenancy;

import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PingController {
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("ok", "true", "tenantId", String.valueOf(TenantContext.getTenantId()));
    }
}
