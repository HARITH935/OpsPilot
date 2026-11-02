# common-events

The events the services send each other through Kafka (from Phase 2): the envelope, the eight saga events, their
names and topics, and their JSON format. A plain Java library (no Spring, no database). Every service depends on it,
so a sender and a receiver can never disagree about the format.

## Build and test
```bash
cd services
./mvnw -pl common-events verify
```

## The envelope
Every event travels in the same wrapper (architecture doc B2.1). Real output of `EventJson.write`:
```json
{"eventId":"780169d9-237d-49a2-a299-a6eac4339da3","eventType":"StockReserved",
 "aggregateId":"7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a","occurredAt":"2026-11-01T06:54:37.133371Z","traceId":null,"version":1,
 "payload":{"orderId":"7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a",
            "items":[{"sku":"SKU-1","qty":2,"unitPrice":89.99},{"sku":"SKU-2","qty":1,"unitPrice":29.99}],"totalAmount":209.97}}
```

| Field | Meaning |
|---|---|
| `eventId` | Unique per event: consumers use it to drop a duplicate delivery (`processed_events`, Phase 2) |
| `eventType` | Which event the payload is (catalog below) |
| `aggregateId` | The order. Also the Kafka key, so one order's events stay in sequence |
| `occurredAt` | When it happened (ISO-8601, UTC) |
| `traceId` | OpenTelemetry trace id, to follow one order across services (Phase 6); `null` until then |
| `version` | Version of the payload format, currently `1` |
| `payload` | The event itself |

`EventEnvelope.of(payload, traceId)` fills in `eventId`, `eventType`, `aggregateId`, `occurredAt` and `version`.
An envelope whose `eventType` or `aggregateId` contradicts its payload is refused.

## The events
| `eventType` | Topic | Sent by | Payload | Who reacts |
|---|---|---|---|---|
| `OrderCreated` | `orders.events` | order | `orderId`, `customerId`, `items[sku, qty]` | inventory reserves the stock |
| `OrderConfirmed` | `orders.events` | order | `orderId` | — (the order is final) |
| `OrderCancelled` | `orders.events` | order | `orderId`, `reason` | inventory releases any held stock |
| `StockReserved` | `inventory.events` | inventory | `orderId`, `items[sku, qty, unitPrice]`, `totalAmount` | order copies the prices ([ADR 0004](../../docs/adr/0004-orders-priced-by-inventory.md)); payment charges `totalAmount` |
| `StockRejected` | `inventory.events` | inventory | `orderId`, `sku`, `reason` | order cancels |
| `StockReleased` | `inventory.events` | inventory | `orderId` | — (compensation done) |
| `PaymentCompleted` | `payments.events` | payment | `orderId`, `paymentId`, `amount` | order confirms; inventory commits the stock |
| `PaymentFailed` | `payments.events` | payment | `orderId`, `paymentId`, `amount`, `reason` | order cancels; inventory releases the stock |

The records all implement the sealed `EventPayload`. `EventType` is the catalog (JSON name ↔ record ↔ topic);
`Topics` holds the topic names as constants.

## JSON
```java
String json = EventJson.write(EventEnvelope.of(new OrderConfirmed(orderId), traceId));

EventEnvelope event = EventJson.read(json);   // the payload is already the right record, chosen by eventType
switch (event.payload()) {
    case PaymentCompleted completed -> confirm(completed.orderId());
    case PaymentFailed failed -> cancel(failed.orderId(), failed.reason());
    default -> { }   // events this service doesn't react to
}
```
- Times are ISO-8601 text, ids are UUID strings, money is exact (`BigDecimal`): `12.50` stays `12.50` when read
  back (decimals are never turned into a `double` on the way).
- **Tolerant reader:** fields it doesn't know are ignored, so a newer service can add one without breaking older
  readers. Reasons are plain strings (not enums) for the same reason.
- An unknown `eventType` is refused with an `IllegalArgumentException`.
- Never rename an `eventType` or a field that is in use, because consumers rely on them. Add new fields instead.
