# ADR 0004 — Orders are priced by inventory during the saga

- **Status:** Accepted
- **Date:** 2026-10-30

## Context
`POST /api/orders` receives only `{customerId, items: [{sku, qty}]}`. Something has to decide each item's price and the
order total, and payment needs that total before it can charge. Prices live in inventory-service (`products.price`).

## Decision
**Inventory is the only price authority.** Orders are priced asynchronously, inside the saga:
1. order-service stores the order as `PENDING` with `total_amount` and `unit_price` **null**, and answers **202 Accepted**.
2. inventory-service reserves stock and publishes `StockReserved` carrying each item's `unitPrice` and the `totalAmount`.
3. order-service copies those prices onto the order; payment-service charges the `totalAmount` from the same event.

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Client sends prices | Anyone could buy anything for 1 rupee. Never trust prices from the client. |
| order-service calls inventory over HTTP when the order is created | Temporal coupling: if inventory is down, nobody can place orders. Adds a synchronous call to an event-driven design. |
| order-service keeps its own copy of the price list (fed by product events) | Total known immediately, but more moving parts and the copy can be stale. |

## Consequences
- For a short time after creation, `GET /api/orders/{id}` shows `totalAmount: null`. This matches 202 Accepted ("still processing").
- The price is locked at reservation time: a later price change does not alter an order that was already reserved.
- The `StockReserved` event (Phase 1.8 / Phase 2) must include per-item `unitPrice` and `totalAmount`.
- Unknown SKUs are rejected by inventory (`StockRejected`), so order-service does not need to know the product catalogue.
