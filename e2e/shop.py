"""The shop as a client sees it: HTTP through the gateway on :8080. Plus two looks behind the scenes that a test
needs: a service's Prometheus metrics, and the messages on a Kafka topic (with Kafka's own tools in its container)."""

import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

GATEWAY = "http://localhost:8080"
KAFKA_CONTAINER = "opspilot-kafka"
KAFKA_BIN = "/opt/kafka/bin"
POLL = 0.3  # seconds between two looks: stays well under the gateway's 10 requests per second without a token


@dataclass
class Answer:
    status: int
    headers: dict[str, str]
    body: Any  # the JSON body, or None
    seconds: float

    @property
    def detail(self) -> str:
        return self.body.get("detail", "") if isinstance(self.body, dict) else ""


def call(method: str, path: str, body: Any = None, base: str = GATEWAY) -> Answer:
    """Any status comes back as an Answer (no exception for 4xx / 5xx): the tests check statuses themselves."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        base + path, method=method, data=data, headers={"Content-Type": "application/json"}
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, headers, raw = response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        status, headers, raw = error.code, dict(error.headers), error.read()
    seconds = time.perf_counter() - start
    # Decimals, not floats: 89.99 * 2 must be exactly 179.98
    parsed = json.loads(raw, parse_float=Decimal) if raw.strip().startswith((b"{", b"[")) else None
    return Answer(status, headers, parsed, seconds)


def wait_until(check: Callable[[], Any], timeout: float, what: str) -> Any:
    """Calls check() until it returns something truthy, and returns that; fails after `timeout` seconds."""
    deadline = time.monotonic() + timeout
    while True:
        result = check()
        if result:
            return result
        if time.monotonic() > deadline:
            raise AssertionError(f"Not within {timeout} s: {what}")
        time.sleep(POLL)


# ── The shop ────────────────────────────────────────────────────────────────────────────────────────────────────────


def place_order(*items: tuple[str, int], customer: str = "e2e") -> str:
    answer = call("POST", "/api/orders", {"customerId": customer, "items": [{"sku": s, "qty": q} for s, q in items]})
    assert answer.status == 202, f"POST /api/orders → {answer.status} {answer.body}"
    return answer.body["orderId"]


def order(order_id: str) -> dict:
    answer = call("GET", f"/api/orders/{order_id}")
    assert answer.status == 200, f"GET /api/orders/{order_id} → {answer.status}"
    return answer.body


def wait_for_outcome(order_id: str, timeout: float = 40) -> dict:
    """The order once the saga has decided: CONFIRMED or CANCELLED (the saga timeout is 30 s, so 40 s is enough)."""
    return wait_until(
        lambda: (o := order(order_id))["status"] in ("CONFIRMED", "CANCELLED") and o,
        timeout,
        f"order {order_id} CONFIRMED or CANCELLED",
    )


def product(sku: str) -> dict:
    answer = call("GET", f"/api/products/{sku}")
    assert answer.status == 200, f"GET /api/products/{sku} → {answer.status}"
    return answer.body


# Stock is read through inventory's product cache (step 2.10). A read that races a stock change can keep an old copy for
# up to the 60 s TTL (the known cache-aside limit), so waits for a stock value allow that long. Normally it's < 1 s.
STOCK_SETTLES = 65


def stock(sku: str) -> tuple[int, int]:
    """(stock, reserved) of a product."""
    found = product(sku)
    return found["stock"], found["reserved"]


def wait_for_stock(sku: str, expected: tuple[int, int]) -> None:
    wait_until(lambda: stock(sku) == expected, STOCK_SETTLES, f"{sku} at (stock, reserved) = {expected}")


def payments_for(order_id: str) -> list[dict]:
    answer = call("GET", "/api/payments?size=100")  # newest first: an order of this test is among them
    assert answer.status == 200, f"GET /api/payments → {answer.status}"
    return [p for p in answer.body["content"] if p["orderId"] == order_id]


# ── Behind the scenes ───────────────────────────────────────────────────────────────────────────────────────────────


def metric(name_and_labels: str, base: str = GATEWAY) -> float:
    """A value from /actuator/prometheus, e.g. 'gateway_ratelimit_rejected_total{key_type="ip",route="products"}';
    0 if it isn't there (yet)."""
    with urllib.request.urlopen(base + "/actuator/prometheus", timeout=10) as response:
        text = response.read().decode()
    match = re.search("^" + re.escape(name_and_labels) + r" (\S+)$", text, re.MULTILINE)
    return float(match.group(1)) if match else 0.0


@dataclass
class Message:
    key: str
    value: str  # the event envelope, as JSON text


def kafka_messages(topic: str) -> list[Message]:
    """Every message on the topic, read from the beginning (Kafka's console consumer, inside its container)."""
    result = subprocess.run(
        [
            "docker",
            "exec",
            KAFKA_CONTAINER,
            f"{KAFKA_BIN}/kafka-console-consumer.sh",
            "--bootstrap-server",
            "localhost:9092",
            "--topic",
            topic,
            "--from-beginning",
            "--timeout-ms",
            "8000",
            "--formatter-property",
            "print.key=true",
            "--formatter-property",
            "key.separator=\t",
        ],
        capture_output=True,
        text=True,
    )
    messages = []
    for line in result.stdout.splitlines():
        if "\t" in line:
            key, value = line.split("\t", 1)
            messages.append(Message(key, value))
    return messages


def kafka_send(topic: str, message: Message) -> None:
    """Sends a message again exactly as it was: the same key, the same bytes (so the same eventId)."""
    subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            KAFKA_CONTAINER,
            f"{KAFKA_BIN}/kafka-console-producer.sh",
            "--bootstrap-server",
            "localhost:9092",
            "--topic",
            topic,
            "--reader-property",
            "parse.key=true",
            "--reader-property",
            "key.separator=\t",
        ],
        input=f"{message.key}\t{message.value}\n",
        text=True,
        check=True,
        capture_output=True,
    )
