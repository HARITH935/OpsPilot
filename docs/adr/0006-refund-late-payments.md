# ADR 0006 — Refund a payment that completed after its order was cancelled

- **Status:** Accepted
- **Date:** 2026-11-03

## Context
Step 2.6a added the saga timeout: an order still `PENDING` after 30 s is cancelled with reason `TIMEOUT`. A slow or
stopped payment-service can still charge that order afterwards, because the `StockReserved` it reacts to is already on
Kafka. order-service then reads `PaymentCompleted` for a `CANCELLED` order: money was taken for an order that will
never be delivered. Until now that was only logged.

Inventory has a related gap. It reads `PaymentCompleted` (`payments.events`) and `OrderCancelled` (`orders.events`)
from two topics, so it may see them in either order. If the late payment comes first, inventory commits the held
stock (the units leave the warehouse), and the `OrderCancelled` behind it finds nothing to release. Those units are lost.

The events in Part B §B2.2 can't express a refund.

## Decision
1. **order-service decides that a refund is needed.** When it reads `PaymentCompleted` for an order that is already
   `CANCELLED`, it writes **`RefundRequested {orderId, paymentId}`** to `orders.events`. The event goes through the
   outbox, in the same transaction as the duplicate mark, so it is sent exactly when the late payment is handled.
2. **payment-service refunds once.** A new listener on `orders.events` (consumer group `payment-service`) handles
   `RefundRequested`:
   - It calls the provider's refund outside any transaction, like a charge.
   - It then marks the payment `REFUNDED` (with `refunded_at`) and announces
     **`PaymentRefunded {orderId, paymentId, amount}`** on `payments.events`.
   - The amount refunded is the one stored with the payment, not a number from the message.
   - A refund the provider refuses is retried by the Kafka error handler (after 0.5, 1 and 2 s). After that, the
     request is parked in `orders.events.dlq` for a human.
3. **Nobody has to react to `PaymentRefunded` yet.** It records that the money went back, for the dashboard and the
   agents later (Phases 9 and 12).
4. **Inventory gives back everything a cancelled order took.** On `OrderCancelled` it releases stock that is still
   held, as before. It now also returns stock that a late payment already committed: the reservation goes from
   `COMMITTED` to `RETURNED`, the units go back into `stock`, and `StockReleased` is announced. An order that was paid
   in time is `CONFIRMED` and is never cancelled, so this only happens in the late case.

The topics table in Part B §B2.2 becomes:

| Topic | Producer | Consumers | Events |
|---|---|---|---|
| `orders.events` | order | inventory, payment | OrderCreated, OrderConfirmed, OrderCancelled, **RefundRequested** |
| `inventory.events` | inventory | order, payment | StockReserved, StockRejected, StockReleased |
| `payments.events` | payment | order, inventory | PaymentCompleted, PaymentFailed, **PaymentRefunded** |

## Alternatives considered
| Option | Why not chosen |
|---|---|
| payment-service reacts to `OrderCancelled` and refunds if it has a completed payment | `OrderCancelled` and `StockReserved` reach payment on two topics, in no fixed order. If the cancel arrives before the charge, there is nothing to refund yet, and the charge that follows is never refunded. order-service sees the cancel and the payment result on the same order row (`@Version`), so it can't miss a late payment. |
| payment-service refuses to charge an order that is already cancelled | Better for the customer (no charge at all). But it needs a call to order-service or its own copy of every order's status, and a charge already on its way to the provider can't be stopped, so refunds are still needed. |
| Wait longer before cancelling, in case the payment still comes | The saga must end within 30 s (Part B §B2.3), and a payment-service outage would keep orders open with no limit. |
| An orchestrator: one service sends a command to each step | A different saga style from the choreography in §B2.3, and a much bigger change than one late-result case. |
| Inventory returns committed stock on `PaymentRefunded` instead of `OrderCancelled` | The units would stay lost while a refund keeps failing or sits in the dead-letter topic. `OrderCancelled` always arrives and doesn't depend on payment. |

## Consequences
- `common-events` has ten events instead of eight. `EventTypeTest` pins the new topics table.
- **`RefundRequested` asks for something, while the other events report facts.** It is still sent like the others, on
  order-service's own topic, so the choreography keeps its rule: every service publishes only to its own topic.
- **payment-service, migration V2:** the status `REFUNDED` and a `refunded_at` column, with a CHECK that the column
  is set exactly when the payment is refunded. A payment can now change once (`COMPLETED` → `REFUNDED`), and its
  `@Version` protects that change.
- **inventory-service, migration V4:** the reservation status `RETURNED`.
- **Duplicates:** a second delivery of `RefundRequested` finds the payment already `REFUNDED` and doesn't call the
  provider. Two deliveries of the same request at the very same moment could both reach the provider. That only
  happens during a consumer hand-over, and only one of them can be saved. A real provider would drop the repeat by
  an idempotency key, the order id; charging has the same caveat.
- **What the customer sees:** the order stays `CANCELLED` / `TIMEOUT`, and `GET /api/payments/{id}` shows
  `REFUNDED` with `refundedAt`.
