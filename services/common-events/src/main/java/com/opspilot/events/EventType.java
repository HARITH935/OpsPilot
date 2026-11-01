package com.opspilot.events;

// The event catalog, in one place: each event's name in the JSON, its payload record, and the topic it is sent to
public enum EventType {

    ORDER_CREATED("OrderCreated", OrderCreated.class, Topics.ORDERS),
    ORDER_CONFIRMED("OrderConfirmed", OrderConfirmed.class, Topics.ORDERS),
    ORDER_CANCELLED("OrderCancelled", OrderCancelled.class, Topics.ORDERS),
    STOCK_RESERVED("StockReserved", StockReserved.class, Topics.INVENTORY),
    STOCK_REJECTED("StockRejected", StockRejected.class, Topics.INVENTORY),
    STOCK_RELEASED("StockReleased", StockReleased.class, Topics.INVENTORY),
    PAYMENT_COMPLETED("PaymentCompleted", PaymentCompleted.class, Topics.PAYMENTS),
    PAYMENT_FAILED("PaymentFailed", PaymentFailed.class, Topics.PAYMENTS);

    private final String wireName;   // the "eventType" value in the JSON; never rename it, consumers rely on it
    private final Class<? extends EventPayload> payloadType;
    private final String topic;

    EventType(String wireName, Class<? extends EventPayload> payloadType, String topic) {
        this.wireName = wireName;
        this.payloadType = payloadType;
        this.topic = topic;
    }

    public static EventType of(EventPayload payload) {
        for (EventType type : values()) {
            if (type.payloadType == payload.getClass()) {
                return type;
            }
        }
        // Can't happen: EventTypeTest checks that every record of the sealed EventPayload is listed above
        throw new IllegalStateException("No EventType for " + payload.getClass().getName());
    }

    public static EventType fromWireName(String wireName) {
        for (EventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown event type '" + wireName + "'");
    }

    public String wireName() {
        return wireName;
    }

    public Class<? extends EventPayload> payloadType() {
        return payloadType;
    }

    public String topic() {
        return topic;
    }
}
