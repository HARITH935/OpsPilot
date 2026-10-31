package com.opspilot.inventory.model;

// HELD → COMMITTED (the order was paid) or HELD → RELEASED (it was not). Both end states are final.
public enum ReservationStatus {
    HELD,
    COMMITTED,
    RELEASED
}
