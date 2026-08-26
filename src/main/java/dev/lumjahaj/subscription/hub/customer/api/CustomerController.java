package dev.lumjahaj.subscription.hub.customer.api;

import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.mapper.CustomerMapper;
import dev.lumjahaj.subscription.hub.customer.app.CustomerService;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(CustomerMapper.toResponse(created));
    }

    @GetMapping
    public ResponseEntity<Page<CustomerResponse>> list(Pageable pageable) {
        Page<CustomerEntity> page = customerService.list(pageable);
        return ResponseEntity.ok(page.map(CustomerMapper::toResponse));
    }
}
