-- Demo catalogue. It is a migration (not a test fixture) because the demo shop needs these products
-- in every environment: local, tests and the public cloud demo. Locust traffic and curl examples order these SKUs.
-- reserved and version start at 0 (column defaults).
INSERT INTO products (sku, name, price, stock) VALUES
    ('SKU-1',  'Mechanical Keyboard',          89.99, 500),
    ('SKU-2',  'Wireless Mouse',               29.99, 800),
    ('SKU-3',  'USB-C Hub',                    39.50, 400),
    ('SKU-4',  '4K Monitor 27-inch',          329.00, 150),
    ('SKU-5',  'Noise-Cancelling Headphones', 199.00, 250),
    ('SKU-6',  'HD Webcam',                    59.90, 300),
    ('SKU-7',  'Laptop Stand',                 34.95, 600),
    ('SKU-8',  'Portable SSD 1 TB',           119.00, 350),
    ('SKU-9',  'Ergonomic Office Chair',      249.00, 100),
    ('SKU-10', 'Limited Edition Desk Mat',     24.99,   3);   -- tiny stock on purpose: sells out fast (out-of-stock demo)
