package dev.lumjahaj.subscription.hub.customer.api;

import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerUpdateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.PaymentMethodRequest;
import dev.lumjahaj.subscription.hub.customer.api.mapper.CustomerMapper;
import dev.lumjahaj.subscription.hub.customer.app.CustomerService;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.common.api.ETags;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerCreateRequest request) {
        CustomerEntity created = customerService.create(request);
        URI location = UriComponentsBuilder.fromPath("/api/customers/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location)
                .eTag(ETags.of(created.getVersion()))
                .body(CustomerMapper.toResponse(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getById(@PathVariable UUID id) {
        CustomerEntity customer = customerService.getById(id);
        return withETag(customer);
    }

    /**
     * Replaces the customer's editable fields. Requires If-Match with the ETag
     * from a previous read: missing is 428, stale is 412, and losing a race
     * to a concurrent write is 409. Without it, two people editing the same
     * customer would silently keep whichever save landed last.
     *
     * The ETag is in a header, not the body, because it is HTTP's own
     * mechanism for this and caches and clients already understand it.
     */
    @PutMapping("/{id}")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<CustomerResponse> update(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CustomerUpdateRequest request
    ) {
        long expectedVersion = ETags.requireIfMatch(ifMatch);
        CustomerEntity customer = customerService.update(id, expectedVersion, request);
        return withETag(customer);
    }

    /**
     * PUT, not POST: storing a payment method is setting one field to a
     * given value, and sending the same token twice must leave the customer
     * in the same state rather than creating anything.
     *
     * The stored token is never returned — the response carries
     * hasDefaultPaymentMethod instead.
     */
    @PutMapping("/{id}/payment-method")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<CustomerResponse> setPaymentMethod(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentMethodRequest request
    ) {
        CustomerEntity customer = customerService.setDefaultPaymentMethod(id, request.paymentMethod());
        return withETag(customer);
    }

    @DeleteMapping("/{id}/payment-method")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<CustomerResponse> clearPaymentMethod(@PathVariable UUID id) {
        CustomerEntity customer = customerService.clearDefaultPaymentMethod(id);
        return withETag(customer);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<CustomerResponse>> list(Pageable pageable) {
        Page<CustomerEntity> page = customerService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, CustomerMapper::toResponse));
    }

    /**
     * Every single-customer response carries its ETag, including the
     * payment-method ones: those writes bump the version too, so a client
     * that just set a payment method holds the ETag its next update needs.
     *
     * Read after the service's transaction has committed, which is when
     * Hibernate flushes the UPDATE and increments the version on this
     * instance - so the ETag is the new version, not the one loaded.
     */
    private static ResponseEntity<CustomerResponse> withETag(CustomerEntity customer) {
        return ResponseEntity.ok()
                .eTag(ETags.of(customer.getVersion()))
                .body(CustomerMapper.toResponse(customer));
    }
}
