package com.opspilot.inventory.controller;

import com.opspilot.inventory.dto.CreateProductRequest;
import com.opspilot.inventory.dto.PageResponse;
import com.opspilot.inventory.dto.ProductResponse;
import com.opspilot.inventory.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import static com.opspilot.inventory.dto.CreateProductRequest.SKU_MESSAGE;
import static com.opspilot.inventory.dto.CreateProductRequest.SKU_PATTERN;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // GET /api/products?page=0&size=20 — sorted by SKU; both parameters are optional
    @GetMapping
    public PageResponse<ProductResponse> listProducts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {   // cap the page size: no "give me everything"
        return productService.listProducts(page, size);
    }

    @GetMapping("/{sku}")   // same SKU rule as POST: a malformed SKU gets 400 before any database lookup
    public ProductResponse getProduct(@PathVariable @Pattern(regexp = SKU_PATTERN, message = SKU_MESSAGE) String sku) {
        return productService.getProduct(sku);
    }

    // 201 Created (orders get 202): the product is complete once stored, nothing else runs afterwards.
    // Admin-only: the API gateway enforces that from Phase 2 (step 4); clients never call this service directly.
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse product = productService.createProduct(request);

        // Relative URL: stays correct behind the gateway / ingress, whatever the public host name is
        return ResponseEntity.created(URI.create("/api/products/" + product.sku())).body(product);
    }
}
