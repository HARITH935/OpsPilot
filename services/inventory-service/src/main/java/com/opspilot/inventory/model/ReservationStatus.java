package com.opspilot.inventory.model;

// HELD → COMMITTED (the order was paid) or HELD → RELEASED (it was not). RELEASED is final. COMMITTED is final too,
// unless the order was cancelled after all (it was paid only after the saga timeout): then COMMITTED → RETURNED.
public enum ReservationStatus {
    HELD,
    COMMITTED,
    RELEASED,
    RETURNED
}
