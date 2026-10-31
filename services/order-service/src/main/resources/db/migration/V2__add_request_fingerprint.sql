-- Idempotency-Key, part 2: remember WHAT was sent with each key, not only the key.
-- Same key + same body  → the retry gets the original order back.
-- Same key + other body → a client bug, rejected with 422.
ALTER TABLE orders ADD COLUMN request_fingerprint VARCHAR(64);   -- SHA-256 of the request, as 64 hex characters

-- A key always comes with its fingerprint, and a fingerprint never comes without a key
ALTER TABLE orders ADD CONSTRAINT orders_idempotency_key_needs_fingerprint
    CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL));
