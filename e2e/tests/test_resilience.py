"""The gateway's circuit breaker (step 2.11a) with a real service going away and coming back."""

import shop
from stack import Stack

OPEN = 'resilience4j_circuitbreaker_state{name="products",state="open"}'
CLOSED = 'resilience4j_circuitbreaker_state{name="products",state="closed"}'


def test_with_inventory_down_the_circuit_opens_and_it_closes_again_once_inventory_is_back(stack: Stack) -> None:
    assert shop.metric(CLOSED) == 1
    stack.stop("inventory")
    try:
        first = shop.call("GET", "/api/products/SKU-1")
        assert first.status == 503
        assert "can't be reached" in first.detail  # retried (GET), then given up

        # Each failed read was tried 4 times, and every try counts: after a few reads the circuit opens
        refused = shop.wait_until(
            lambda: (a := shop.call("GET", "/api/products/SKU-1")).headers.get("Retry-After") and a,
            15,
            "the products circuit to open",
        )
        assert refused.status == 503
        assert "failed too often" in refused.detail
        assert shop.metric(OPEN) == 1

        quick = shop.call("GET", "/api/products/SKU-1")
        assert quick.status == 503
        assert quick.seconds < 0.1  # refused at once: inventory isn't even tried
    finally:
        stack.start("inventory")

    # After the break (5 s in these tests) the breaker lets 3 test reads through. They find inventory well, and it
    # closes again. Only reads decide (each poll is one); looking at the metrics doesn't go through the breaker.
    shop.wait_until(
        lambda: shop.call("GET", "/api/products/SKU-1").status == 200 and shop.metric(CLOSED) == 1,
        40,
        "products answering and the circuit closed",
    )
