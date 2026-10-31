package com.opspilot.inventory.repository;

import com.opspilot.inventory.TestcontainersConfiguration;
import com.opspilot.inventory.model.Product;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Loads only the JPA layer (entities, repositories, Flyway incl. the V2 seed data) against a real Postgres container.
// Each test runs in a transaction that is rolled back afterwards, so tests don't affect each other.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void seedMigrationLoadsTheDemoCatalogue() {
        List<Product> products = productRepository.findAll();

        assertThat(products).hasSize(10);
        assertThat(products).allSatisfy(product -> assertThat(product.getReserved()).isZero());
        assertThat(productRepository.findById("SKU-10")).map(Product::getStock).contains(3);   // the "sells out fast" one
    }

    @Test
    void savesProductAndReadsItBack() {
        productRepository.saveAndFlush(new Product("TEST-1", "Test Product", new BigDecimal("12.50"), 7));
        entityManager.clear();   // forget cached objects so the next read really hits the database

        Product loaded = productRepository.findById("TEST-1").orElseThrow();

        assertThat(loaded.getName()).isEqualTo("Test Product");
        assertThat(loaded.getPrice()).isEqualByComparingTo("12.50");
        assertThat(loaded.getStock()).isEqualTo(7);
        assertThat(loaded.getAvailable()).isEqualTo(7);
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void savingAnExistingSkuFailsInsteadOfOverwritingIt() {
        // A new Product (version still null) is always INSERTed, so the primary key refuses the duplicate
        assertThatThrownBy(() -> productRepository.saveAndFlush(
                new Product("SKU-1", "Imposter", new BigDecimal("1.00"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // One test, run once per row. Even SQL written by hand (bypassing the Java checks) cannot break the rules:
    // the CHECK constraints refuse it.
    @ParameterizedTest
    @CsvSource({
            "0.00, 5,  0, products_price_check",            // a price must be greater than 0
            "9.99, 5, -1, products_reserved_check",         // a negative reservation makes no sense
            "9.99, 5,  6, products_reserved_within_stock"   // can't hold more units than are on hand
    })
    void databaseRefusesInvalidProducts(BigDecimal price, int stock, int reserved, String constraint) {
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO products (sku, name, price, stock, reserved, version)
                        VALUES ('TEST-BAD', 'Bad Product', :price, :stock, :reserved, 0)
                        """)
                .setParameter("price", price)
                .setParameter("stock", stock)
                .setParameter("reserved", reserved)
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining(constraint);
    }
}
