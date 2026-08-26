package dev.lumjahaj.subscription.hub.catalog.api;

import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.ProductMapper;
import dev.lumjahaj.subscription.hub.catalog.app.ProductService;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        ProductEntity created = productService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ProductMapper.toResponse(created));
    }

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> list(Pageable pageable) {
        Page<ProductEntity> page = productService.list(pageable);
        return ResponseEntity.ok(page.map(ProductMapper::toResponse));
    }
}