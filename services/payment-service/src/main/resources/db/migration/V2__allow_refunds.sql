-- payment-service schema, version 2: a completed payment can be refunded once (step 2.6b, ADR 0006).
-- Never edit this file after it is merged: add the next V<n>__... instead.

-- When the money went back; empty for every payment that was not refunded
ALTER TABLE payments ADD COLUMN refunded_at TIMESTAMPTZ;

-- Replace V1's status rule (Postgres named it payments_status_check) with one that also allows REFUNDED
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('COMPLETED', 'FAILED', 'REFUNDED'));

-- A refund time exactly when the payment was refunded
ALTER TABLE payments ADD CONSTRAINT payments_refunded_at_only_when_refunded
    CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL));
