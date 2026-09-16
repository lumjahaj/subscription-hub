package dev.lumjahaj.subscription.hub.customer.api;

import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.PaymentMethodRequest;
import dev.lumjahaj.subscription.hub.customer.api.mapper.CustomerMapper;
import dev.lumjahaj.subscription.hub.customer.app.CustomerService;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
        return ResponseEntity.created(location).body(CustomerMapper.toResponse(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getById(@PathVariable UUID id) {
        CustomerEntity customer = customerService.getById(id);
        return ResponseEntity.ok(CustomerMapper.toResponse(customer));
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
        return ResponseEntity.ok(CustomerMapper.toResponse(customer));
    }

    @DeleteMapping("/{id}/payment-method")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<CustomerResponse> clearPaymentMethod(@PathVariable UUID id) {
        CustomerEntity customer = customerService.clearDefaultPaymentMethod(id);
        return ResponseEntity.ok(CustomerMapper.toResponse(customer));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<CustomerResponse>> list(Pageable pageable) {
        Page<CustomerEntity> page = customerService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, CustomerMapper::toResponse));
    }
}
