# ADR 0007 — The payment provider's circuit breaker judges payments, not attempts

- **Status:** Accepted
- **Date:** 2026-11-04

## Context
Step 2.11b protects payment-service's calls to the payment provider with Resilience4j. It uses a timeout, retries
after a refusal (`PROVIDER_ERROR`), a circuit breaker and a bulkhead. Part B §B2.4 sets the breaker's defaults:
open at 50% failures over 20 calls, for 30 s. It doesn't say whether a "call" is a single attempt or a whole payment
including its retries.

The first version used the same order as the gateway (step 2.11a): **Retry ( CircuitBreaker ( … ) )**. So every
attempt counted. The real run of chaos scenario C12 (`PAYMENT_FAILURE_RATE=0.5`) showed what that does:
- About half of all attempts fail, which is right at the 50% threshold. The breaker opened within the first 20 attempts.
- In half-open, its 3 test calls failed half the time too, so it kept opening again.
- While open, every payment failed at once with `PROVIDER_UNAVAILABLE`.

Result: only **6 of 36** payments went through in one run and **21 of 36** in another. Without any protection it
would be about 50%; with retries alone about 87.5% (1 − 0.5³).

The gateway is different. It retries only "couldn't reach the service" and 502 / 503, which mean the service is down,
so counting every attempt opens its breaker sooner during an outage, which is the point there.

## Decision
The payment provider's breaker sits **outside** the retries:

```
CircuitBreaker ( Retry ( TimeLimiter ( Bulkhead ( provider ) ) ) )
```

- A payment counts **once**, after its retries. One that works on its second or third try is a success; one that
  still fails is a failure.
- The breaker opens when 50% of the last 20 **payments** failed. B2.4's numbers are unchanged.
- An open breaker still refuses at once, with no attempt and no retries.

## Consequences
- C12 now behaves like a provider with problems, not a provider that is down. Real run: **37 of 40** orders were
  confirmed, 3 failed after their retries, and the breaker never opened.
- A provider that is fully down is detected more slowly. The breaker opens after 20 payments, which is 60 attempts.
  Real run: 20 payments each took about 1 s (3 tries) before the breaker opened. The next 10 were refused about
  10 ms apart.
- The provider gets up to 3 times the traffic while it fails, until the breaker opens. That's acceptable for a payment
  provider, where a lost order costs more than an extra request.
- `ResilientPaymentProviderTest` pins this down. A provider that refuses 2 of every 3 attempts must keep the breaker
  closed. A provider that is fully down must open it after exactly 20 payments (60 calls). Moving the breaker back
  inside the retries fails both tests.
- The gateway keeps Retry ( CircuitBreaker ( … ) ) (step 2.11a), for the reason given above.
