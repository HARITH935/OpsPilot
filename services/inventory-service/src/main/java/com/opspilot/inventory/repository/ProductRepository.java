package com.opspilot.inventory.repository;

import com.opspilot.inventory.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

// The key type is String because products are looked up by SKU.
// findAll(pageable), findById, existsById and saveAndFlush are inherited, so no extra methods are needed yet.
public interface ProductRepository extends JpaRepository<Product, String> {
}
