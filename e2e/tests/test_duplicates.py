"""Kafka delivers "at least once": the same event can arrive twice (architecture doc B2.2). The test sends a real
event a second time, byte for byte, and checks that nothing happens twice."""

import shop
from stack import Stack


def test_the_same_stock_reserved_event_twice_charges_the_order_once(stack: Stack) -> None:
    stock, reserved = shop.stock("SKU-3")
    order_id = shop.place_order(("SKU-3", 1))
    order = shop.wait_for_outcome(order_id)
    assert order["status"] == "CONFIRMED"
    shop.wait_for_stock("SKU-3", (stock - 1, reserved))  # the first delivery, completely done

    [event] = [
        m
        for m in shop.kafka_messages("inventory.events")
        if m.key == order_id and '"eventType":"StockReserved"' in m.value
    ]
    log_so_far = len(stack["payment"].log())
    shop.kafka_send("inventory.events", event)  # the same key, the same eventId

    # payment-service received it and said no to a second charge...
    stack["payment"].wait_for_log(
        f"Order {order_id} already has a COMPLETED payment; not charging again", after=log_so_far
    )
    # ...so there is still exactly one payment, and nothing else changed
    [payment] = shop.payments_for(order_id)
    assert payment["status"] == "COMPLETED"
    assert shop.order(order_id)["status"] == "CONFIRMED"
    assert shop.order(order_id)["totalAmount"] == order["totalAmount"]
    assert shop.stock("SKU-3") == (stock - 1, reserved)  # taken once, not twice
