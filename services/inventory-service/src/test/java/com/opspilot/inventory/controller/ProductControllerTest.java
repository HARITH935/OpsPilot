package com.opspilot.inventory.controller;

import com.opspilot.inventory.dto.CreateProductRequest;
import com.opspilot.inventory.dto.PageResponse;
import com.opspilot.inventory.dto.ProductResponse;
import com.opspilot.inventory.exception.ProductAlreadyExistsException;
import com.opspilot.inventory.exception.ProductNotFoundException;
import com.opspilot.inventory.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Loads only the web layer (this controller + the error handler). The service is a Mockito mock: no database.
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final ProductResponse KEYBOARD =
            new ProductResponse("SKU-1", "Mechanical Keyboard", new BigDecimal("89.99"), 500, 20, 480);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void listProductsReturnsAPage() throws Exception {
        when(productService.listProducts(1, 5)).thenReturn(new PageResponse<>(List.of(KEYBOARD), 1, 5, 6, 2));

        mockMvc.perform(get("/api/products").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("SKU-1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listProductsUsesSensibleDefaults() throws Exception {
        when(productService.listProducts(0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());

        verify(productService).listProducts(0, 20);   // first page, 20 per page
    }

    @Test
    void listProductsRejectsATooLargePageSize() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.size").value("must be less than or equal to 100"));

        verifyNoInteractions(productService);
    }

    @Test
    void getProductReturnsTheProduct() throws Exception {
        when(productService.getProduct("SKU-1")).thenReturn(KEYBOARD);

        mockMvc.perform(get("/api/products/{sku}", "SKU-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.price").value(89.99))
                .andExpect(jsonPath("$.reserved").value(20))
                .andExpect(jsonPath("$.available").value(480));
    }

    @Test
    void unknownProductReturns404Problem() throws Exception {
        when(productService.getProduct("SKU-404")).thenThrow(new ProductNotFoundException("SKU-404"));

        mockMvc.perform(get("/api/products/{sku}", "SKU-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.detail").value("Product SKU-404 does not exist"));
    }

    @Test
    void malformedSkuInTheUrlReturns400Problem() throws Exception {
        mockMvc.perform(get("/api/products/{sku}", "sku-1"))   // lower case is not a valid SKU
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.sku").value(CreateProductRequest.SKU_MESSAGE));

        verifyNoInteractions(productService);   // rejected before any database lookup
    }

    @Test
    void createProductReturns201WithLocation() throws Exception {
        when(productService.createProduct(any())).thenReturn(
                new ProductResponse("TEST-1", "Test Product", new BigDecimal("12.50"), 7, 0, 7));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "TEST-1", "name": "Test Product", "price": 12.50, "stock": 7}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/TEST-1"))
                .andExpect(jsonPath("$.sku").value("TEST-1"))
                .andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void invalidFieldsReturn400ProblemNamingEachField() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "bad sku", "name": "", "price": 0, "stock": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.sku").value(CreateProductRequest.SKU_MESSAGE))
                .andExpect(jsonPath("$.errors.name").value("must not be blank"))
                .andExpect(jsonPath("$.errors.price").value("must be greater than 0"))
                .andExpect(jsonPath("$.errors.stock").value("must be greater than or equal to 0"));

        verifyNoInteractions(productService);   // invalid input never reaches the business logic
    }

    @Test
    void priceWithFractionsOfACentIsRejected() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "TEST-1", "name": "Test Product", "price": 9.999, "stock": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price")
                        .value("numeric value out of bounds (<10 digits>.<2 digits> expected)"));
    }

    @Test
    void duplicateSkuReturns409Problem() throws Exception {
        when(productService.createProduct(any())).thenThrow(new ProductAlreadyExistsException("SKU-1"));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "SKU-1", "name": "Imposter", "price": 1.00, "stock": 1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Product already exists"))
                .andExpect(jsonPath("$.detail").value("Product SKU-1 already exists"));
    }
}
