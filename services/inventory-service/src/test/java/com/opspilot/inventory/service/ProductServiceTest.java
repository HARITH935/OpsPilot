package com.opspilot.inventory.service;

import com.opspilot.inventory.dto.CreateProductRequest;
import com.opspilot.inventory.dto.PageResponse;
import com.opspilot.inventory.dto.ProductResponse;
import com.opspilot.inventory.exception.ProductAlreadyExistsException;
import com.opspilot.inventory.exception.ProductNotFoundException;
import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pure unit test: the repository is a Mockito mock, so there is no Spring and no database
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final CreateProductRequest REQUEST =
            new CreateProductRequest("TEST-1", "Test Product", new BigDecimal("12.50"), 7);

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        // withoutTransaction(): just runs the code — there is no database in this test
        productService = new ProductService(productRepository, TransactionOperations.withoutTransaction());
    }

    @Test
    void listProductsReturnsOnePageSortedBySku() {
        Product keyboard = new Product("SKU-1", "Mechanical Keyboard", new BigDecimal("89.99"), 500);
        when(productRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(keyboard), PageRequest.of(0, 20), 1));

        PageResponse<ProductResponse> page = productService.listProducts(0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);   // catches the Pageable we sent
        verify(productRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("sku").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(page.content()).extracting(ProductResponse::sku).containsExactly("SKU-1");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void getProductReturnsTheProductWithWhatIsAvailable() {
        when(productRepository.findById("SKU-1"))
                .thenReturn(Optional.of(new Product("SKU-1", "Mechanical Keyboard", new BigDecimal("89.99"), 500)));

        ProductResponse response = productService.getProduct("SKU-1");

        assertThat(response.name()).isEqualTo("Mechanical Keyboard");
        assertThat(response.available()).isEqualTo(500);
    }

    @Test
    void getProductThrowsWhenTheSkuIsUnknown() {
        when(productRepository.findById("SKU-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct("SKU-404"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("SKU-404");
    }

    @Test
    void createProductSavesANewProduct() {
        when(productRepository.existsById("TEST-1")).thenReturn(false);

        ProductResponse response = productService.createProduct(REQUEST);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSku()).isEqualTo("TEST-1");
        assertThat(saved.getValue().getPrice()).isEqualByComparingTo("12.50");
        assertThat(saved.getValue().getStock()).isEqualTo(7);
        assertThat(response.available()).isEqualTo(7);
    }

    @Test
    void createProductRefusesASkuThatAlreadyExists() {
        when(productRepository.existsById("TEST-1")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(REQUEST))
                .isInstanceOf(ProductAlreadyExistsException.class);
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void losingARaceForTheSameSkuIsReportedAsAlreadyExists() {
        when(productRepository.existsById("TEST-1"))
                .thenReturn(false)    // 1st look: the other request hasn't committed yet
                .thenReturn(true);    // after our insert failed: the other request's product is there
        when(productRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> productService.createProduct(REQUEST))
                .isInstanceOf(ProductAlreadyExistsException.class);
    }

    @Test
    void otherDatabaseErrorsAreNotHidden() {
        when(productRepository.existsById("TEST-1")).thenReturn(false);   // still no such product after the failure
        when(productRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check constraint"));

        assertThatThrownBy(() -> productService.createProduct(REQUEST))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
