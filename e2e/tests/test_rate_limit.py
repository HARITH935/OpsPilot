"""The gateway's rate limiter (step 2.9a) from the outside: a burst of requests without a token."""

import time

import shop
from stack import Stack

REJECTED = 'gateway_ratelimit_rejected_total{key_type="ip",route="products"}'


def test_a_burst_past_the_limit_gets_429_with_retry_after_and_is_counted(stack: Stack) -> None:
    time.sleep(1.1)  # start with a full budget: nothing from earlier tests in the last second
    rejected_before = shop.metric(REJECTED)

    try:
        answers = [shop.call("GET", "/api/products?size=1") for _ in range(15)]  # as fast as possible: ~10 ms each
    finally:
        time.sleep(1.1)  # leave a full budget behind: the next test's first request must not get a 429

    ok = [a for a in answers if a.status == 200]
    refused = [a for a in answers if a.status == 429]
    assert len(ok) + len(refused) == 15
    assert len(ok) == 10  # the budget without a token: 10 per second per route
    for answer in refused:
        assert int(answer.headers["Retry-After"]) >= 1
        assert answer.headers["X-RateLimit-Remaining"] == "0"
    assert shop.metric(REJECTED) == rejected_before + len(refused)
