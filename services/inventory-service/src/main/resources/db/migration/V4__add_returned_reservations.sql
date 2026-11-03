-- Reservations, version 2: stock that a late payment committed can come back (step 2.6b, ADR 0006).
-- A payment that completed after the saga timeout may reach inventory before the order's OrderCancelled: the units
-- already left the warehouse (COMMITTED), and OrderCancelled then puts them back: COMMITTED → RETURNED.
-- Never edit this file after it is merged: add the next V<n>__... instead.

-- Replace V3's status rule (Postgres named it reservations_status_check) with one that also allows RETURNED
ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check
    CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED', 'RETURNED'));
