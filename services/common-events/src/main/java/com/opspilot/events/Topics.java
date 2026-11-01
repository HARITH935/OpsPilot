package com.opspilot.events;

// Kafka topic names (architecture doc B2.2). Constants, so code like @KafkaListener(topics = Topics.ORDERS) can use them.
public final class Topics {

    public static final String ORDERS = "orders.events";
    public static final String INVENTORY = "inventory.events";
    public static final String PAYMENTS = "payments.events";

    private Topics() {
        // constants only
    }
}
