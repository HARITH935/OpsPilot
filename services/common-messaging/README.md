# common-messaging

The **transactional outbox**, shared by all services (from Phase 2, [ADR 0005](../../docs/adr/0005-phase2-foundations.md)):
a service saves an event in the same database transaction as its business change, and a background job sends it to
Kafka afterwards. A Spring library (a normal `.jar`): a service uses it by importing `MessagingConfiguration`.
Idempotent consumers and dead-letter topics follow in step 2.3.

## Why an outbox
A service must change its own data **and** tell the others. Done as two separate steps, a crash in between either
loses the event (the saga is stuck) or announces a change that was never saved. With the outbox, the event is just
another row written in the same transaction: both are saved, or neither. Sending happens later, and may be repeated.

```
OrderService.createOrder ──one transaction──►  orders row  +  outbox row (published_at = NULL)
OutboxPublisher (every 0.5 s) ──► lock unsent rows ──► send to Kafka, wait for "stored" ──► published_at = now()
```

## Use it in a service
```java
@Configuration(proxyBeanMethods = false)
@EnableScheduling                       // the publisher is a @Scheduled job
@Import(MessagingConfiguration.class)
class MessagingConfig { }
```
```java
@Transactional
public void reserve(...) {
    ...                                                     // the business change
    outboxWriter.write(new StockReserved(orderId, items, total));   // same transaction
}
```
Plus `spring.kafka.bootstrap-servers` in the service's `application.yml`, and the `outbox` table (every service's
`V1__init.sql` creates it). Put the two annotations on a separate `@Configuration` class, not on the application
class: test slices such as `@WebMvcTest` skip such classes, so controller tests don't need Kafka.

## How it behaves
| Part | Behaviour |
|---|---|
| `OutboxWriter.write(payload)` | Wraps the payload in an `EventEnvelope` and inserts it (`id` = event id, `payload` = the envelope JSON). **Refuses to run without a transaction** (`Propagation.MANDATORY`). |
| `OutboxPublisher` | Every 0.5 s, in one transaction per batch of up to 100: `SELECT … FOR UPDATE SKIP LOCKED` the oldest unsent rows, send each to its event type's topic with **key = order id**, wait for Kafka's acknowledgement, mark it published. A full batch is followed by the next one at once. |
| Kafka down | The send fails within ~5–10 s; the publisher logs one WARN, **stops the batch** (so no later event overtakes the failed one) and retries in the next run. The service keeps accepting requests; events wait in the table. |
| Several copies of a service | `SKIP LOCKED` makes each publisher skip rows another one has locked: every event is sent once, in parallel. |
| Delivery guarantee | **At-least-once.** If the service dies after Kafka stored an event but before the row is marked, the event is sent again, so consumers drop duplicates by `eventId` (step 2.3). |

Producer settings (`MessagingConfiguration`): `acks=all` (sent = stored), `max.block.ms=5000`,
`request.timeout.ms=5000`, `delivery.timeout.ms=10000` (the defaults would wait up to 1–2 minutes). Spring's default
producer listener, which would log every failure a second time as an ERROR with the event's content, is replaced by
a silent one: the publisher's WARN line is enough.

The table is read and written with plain SQL (`JdbcClient`), not a JPA entity: the important SQL (the lock, and the
"insert if not seen before" check in step 2.3) is SQL anyway, and services don't have to scan shared entities.
Postgres hands `jsonb` back with its keys reordered, so the publisher reads the envelope and writes it again with
`EventJson`: Kafka always gets exactly the contract's format.

## Build and test
```bash
cd services
./mvnw -pl common-messaging -am verify
```
Needs Docker: the tests start a throwaway Postgres (with the outbox table from `src/test/resources/schema.sql`) and a
throwaway Kafka (`apache/kafka:4.2.2`, topics with 3 partitions, auto-create off).

| Test | Proves |
|---|---|
| `OutboxWriterIntegrationTest` | the row holds the whole envelope; no transaction → refused; rollback → no event |
| `OutboxPublisherTest` (Mockito) | topic + key; stops at the first failed send and marks only what Kafka stored; Kafka unreachable → nothing marked; a full batch is followed by the next |
| `OutboxPublisherIntegrationTest` | each event sent once, in the contract format, one order's events in one partition and in order; events go to their type's topic; **4 publishers at once on 300 events → each sent exactly once** (without the lock: 1200 sends); producer settings applied |
