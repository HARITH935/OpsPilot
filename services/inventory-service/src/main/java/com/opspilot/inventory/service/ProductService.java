package com.opspilot.inventory.service;

import com.opspilot.inventory.dto.CreateProductRequest;
import com.opspilot.inventory.dto.PageResponse;
import com.opspilot.inventory.dto.ProductResponse;
import com.opspilot.inventory.exception.ProductAlreadyExistsException;
import com.opspilot.inventory.exception.ProductNotFoundException;
import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final TransactionOperations transactions;

    public ProductService(ProductRepository productRepository, TransactionOperations transactions) {
        this.productRepository = productRepository;
        this.transactions = transactions;   // Spring Boot provides a TransactionTemplate: "run this block in a transaction"
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listProducts(int page, int size) {
        // The SKU is unique, so sorting by it alone keeps pages stable
        PageRequest pageable = PageRequest.of(page, size, Sort.by("sku"));
        return PageResponse.from(productRepository.findAll(pageable).map(ProductResponse::from));
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(String sku) {
        return productRepository.findById(sku)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(sku));
    }

    /**
     * Adds a product to the catalogue. An existing SKU is refused with ProductAlreadyExistsException, also when two
     * requests race: the primary key lets only one insert win (like the Idempotency-Key in order-service).
     * Deliberately not @Transactional: the insert runs in its own transaction so a duplicate-key failure can be caught here.
     */
    public ProductResponse createProduct(CreateProductRequest request) {
        if (productRepository.existsById(request.sku())) {
            throw new ProductAlreadyExistsException(request.sku());
        }

        try {
            return transactions.execute(tx -> insert(request));
        } catch (DataIntegrityViolationException e) {
            if (productRepository.existsById(request.sku())) {
                // Race: another request inserted this SKU between our check and our insert
                log.info("Product {} was created concurrently; refusing the duplicate", request.sku());
                throw new ProductAlreadyExistsException(request.sku());
            }
            throw e;   // a different database rule failed: a real bug, so let it surface as a 500
        }
    }

    private ProductResponse insert(CreateProductRequest request) {
        Product product = new Product(request.sku(), request.name(), request.price(), request.stock());
        productRepository.saveAndFlush(product);   // flush now, so a duplicate SKU fails here — inside the try/catch above

        log.info("Product {} created with stock {}", product.getSku(), product.getStock());
        return ProductResponse.from(product);
    }
}
