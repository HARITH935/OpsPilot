"""The order saga across all services, from the outside: an order goes in at the gateway, and the tests watch how it
ends and what it did to the stock and the money (architecture doc B2.3)."""

from decimal import Decimal

import shop
from stack import Stack


def test_an_order_is_confirmed_paid_and_its_stock_leaves_the_warehouse(stack: Stack) -> None:
    stock, reserved = shop.stock("SKU-1")
    price = shop.product("SKU-1")["price"]

    order_id = shop.place_order(("SKU-1", 2))
    order = shop.wait_for_outcome(order_id)

    assert order["status"] == "CONFIRMED"
    assert Decimal(order["totalAmount"]) == 2 * Decimal(price)  # priced by inventory (ADR 0004)
    [payment] = shop.payments_for(order_id)
    assert payment["status"] == "COMPLETED"
    assert payment["amount"] == order["totalAmount"]
    # The two units left the warehouse, nothing is still held for this order
    shop.wait_for_stock("SKU-1", (stock - 2, reserved))


def test_an_order_for_more_than_is_left_is_cancelled_and_nothing_is_charged(stack: Stack) -> None:
    stock, reserved = shop.stock("SKU-10")  # the limited desk mat: only a few in stock
    too_many = stock - reserved + 1

    order_id = shop.place_order(("SKU-10", too_many))
    order = shop.wait_for_outcome(order_id)

    assert order["status"] == "CANCELLED"
    assert order["cancelReason"] == "OUT_OF_STOCK"
    assert shop.payments_for(order_id) == []  # payment never saw it: there was nothing to charge
    assert shop.stock("SKU-10") == (stock, reserved)


def test_a_failed_payment_cancels_the_order_and_gives_the_stock_back(stack: Stack) -> None:
    stack.restart("payment", PAYMENT_FAILURE_RATE="1.0")  # a provider that refuses everything
    try:
        stock, reserved = shop.stock("SKU-2")

        order_id = shop.place_order(("SKU-2", 1))
        order = shop.wait_for_outcome(order_id)

        assert order["status"] == "CANCELLED"
        assert order["cancelReason"] == "PAYMENT_FAILED"
        [payment] = shop.payments_for(order_id)
        assert payment["status"] == "FAILED"
        assert payment["failureReason"] == "PROVIDER_ERROR"  # refused, also after its two retries
        # The compensation: the held unit is on sale again, exactly as before
        shop.wait_for_stock("SKU-2", (stock, reserved))
    finally:
        stack.restart("payment")  # a healthy provider again for the other tests
