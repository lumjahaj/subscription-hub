package dev.lumjahaj.subscription.hub.catalog.api;

import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.ProductMapper;
import dev.lumjahaj.subscription.hub.catalog.app.ProductService;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

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
        URI location = UriComponentsBuilder.fromPath("/api/products/{code}")
                .buildAndExpand(created.getCode())
                .toUri();
        return ResponseEntity.created(location).body(ProductMapper.toResponse(created));
    }

    @GetMapping("/{code}")
    public ResponseEntity<ProductResponse> getByCode(@PathVariable String code) {
        ProductEntity product = productService.getByCode(code);
        return ResponseEntity.ok(ProductMapper.toResponse(product));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ProductResponse>> list(Pageable pageable) {
        Page<ProductEntity> page = productService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, ProductMapper::toResponse));
    }
}