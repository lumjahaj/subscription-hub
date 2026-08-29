package dev.lumjahaj.subscription.hub.customer.api;

import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.mapper.CustomerMapper;
import dev.lumjahaj.subscription.hub.customer.app.CustomerService;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import jakarta.validation.Valid;
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

    @GetMapping
    public ResponseEntity<PagedResponse<CustomerResponse>> list(Pageable pageable) {
        Page<CustomerEntity> page = customerService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, CustomerMapper::toResponse));
    }
}
