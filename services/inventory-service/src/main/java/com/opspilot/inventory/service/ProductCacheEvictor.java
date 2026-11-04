package com.opspilot.inventory.service;

import com.opspilot.inventory.model.Product;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A JPA entity listener on Product (@EntityListeners): Hibernate calls it each time it writes a product row, whatever
 * the reason (reserve, commit, release, return, a new product). It deletes that product's cached copy once the
 * transaction commits. One place for all changes, so a new way of changing stock can't forget the cache.
 *
 * <p>Changes made with plain SQL (not through Hibernate) are not seen here: their copies stay for up to the TTL.
 *
 * <p>Hibernate asks Spring to create this class, so it can be given Spring beans. ObjectProvider, because not every
 * test starts the cache (a @DataJpaTest has only the database part): then there is nothing to delete.
 */
public class ProductCacheEvictor {

    private final ObjectProvider<ProductCache> cache;

    public ProductCacheEvictor(ObjectProvider<ProductCache> cache) {
        this.cache = cache;
    }

    @PostPersist   // after an INSERT: a product created again under an old SKU must not show the old copy
    @PostUpdate    // after an UPDATE: stock or reserved changed
    @PostRemove    // after a DELETE
    void productWritten(Product product) {
        cache.ifAvailable(productCache -> productCache.evictAfterCommit(product.getSku()));
    }
}
