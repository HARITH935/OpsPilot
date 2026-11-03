"""Shop traffic for the OpsPilot demo, sent through the API gateway (step 2.9b).

Two kinds of shopper browse the catalogue, look at products and now and then place an order:
- AnonymousShopper sends no token, so the gateway counts it by IP address. All of them run on this machine,
  so together they share one budget: 10 requests per second per route by default.
- LoggedInShopper sends a token, so the gateway counts it by the token's "sub": 20 requests per second per route
  for each demo user. They log in once at the start and share the three demo logins, the way a real client keeps
  its token instead of logging in again (login has its own strict limit: 10 per minute).

Every shopper waits a random 0.5 to 1.5 s between two things, so it does about one per second: the load in requests
per second is about the number of shoppers.
How to run it and read the results: README.md.
"""

import itertools
import logging
import os
import random
from collections import deque
from typing import Any
from uuid import uuid4

from locust import HttpUser, between, events, task
from locust.clients import HttpSession, ResponseContextManager
from locust.env import Environment
from locust.exception import StopTest

from checks import RATE_LIMITED, exit_code, problem_with

log = logging.getLogger(__name__)

DEMO_USERS = ("viewer", "engineer", "admin")
# Products from inventory's seed data. SKU-10 is left out: it has only 3 in stock, for the out-of-stock demo.
SKUS = [f"SKU-{n}" for n in range(1, 10)]

tokens: list[str] = []  # one per demo user, filled by log_in() when the test starts
next_token = itertools.count()  # hands the tokens out in turn: viewer, engineer, admin, viewer, ...


class Shopper(HttpUser):
    abstract = True  # not run itself: only the two kinds below
    host = "http://localhost:8080"  # the gateway; --host overrides it
    # Random, like real shoppers. With a fixed beat all shoppers fire at the same moment, and the strict sliding
    # window then lets far fewer through than the limit (measured: about 6 instead of about 9 per second; README.md).
    wait_time = between(0.5, 1.5)

    def on_start(self) -> None:
        self.customer_id = f"locust-{uuid4().hex[:8]}"
        self.order_ids: deque[str] = deque(maxlen=10)  # its last few orders, to look at again

    @task(5)
    def browse_catalogue(self) -> None:
        self.call("GET", "/api/products", 200, params={"page": 0, "size": 10})

    @task(5)
    def view_product(self) -> None:
        # name: report all SKUs as one line in the results, not nine
        self.call("GET", f"/api/products/{random.choice(SKUS)}", 200, name="/api/products/{sku}")

    @task(1)
    def place_order(self) -> None:
        items = [{"sku": sku, "qty": 1} for sku in random.sample(SKUS, k=random.randint(1, 2))]
        response = self.call("POST", "/api/orders", 202, json={"customerId": self.customer_id, "items": items})
        if response.status_code == 202:
            self.order_ids.append(response.json()["orderId"])

    @task(2)
    def check_order(self) -> None:
        if self.order_ids:
            self.call("GET", f"/api/orders/{random.choice(self.order_ids)}", 200, name="/api/orders/{id}")

    def call(
        self, method: str, path: str, expected: int, name: str | None = None, **kwargs: Any
    ) -> ResponseContextManager:
        # catch_response: we decide whether the answer counts as a success, instead of Locust's "below 400 = ok"
        with self.client.request(method, path, name=name or path, catch_response=True, **kwargs) as response:
            problem = problem_with(response.status_code, response.headers, expected)
            if problem:
                response.failure(problem)
        return response


class AnonymousShopper(Shopper):
    pass


class LoggedInShopper(Shopper):
    def __init__(self, environment: Environment) -> None:
        super().__init__(environment)
        if not tokens:
            # Locust's way to end the whole run from here (it does the same when --host is missing)
            raise StopTest("The logged-in shoppers have no token, because the login failed (see above)")
        token = tokens[next(next_token) % len(tokens)]
        self.client.headers["Authorization"] = f"Bearer {token}"  # sent with every request of this shopper


@events.test_start.add_listener
def log_in(environment: Environment, **_kwargs: object) -> None:
    """Gets one token per demo user, before any shopper starts. If that fails, the run stops with exit code 1."""
    tokens.clear()
    if LoggedInShopper not in environment.user_classes:  # e.g. "locust AnonymousShopper": no login needed
        return
    password = os.environ.get("DEMO_USERS_PASSWORD")
    if not password:
        login_failed(environment, "DEMO_USERS_PASSWORD is not set")
        return

    # Locust's own HTTP client, so the logins show in the results as /auth/token
    session = HttpSession(environment.host or Shopper.host, environment.events.request, user=None)
    new_tokens = []
    for username in DEMO_USERS:
        body = {"username": username, "password": password}
        with session.post("/auth/token", json=body, catch_response=True) as response:
            problem = problem_with(response.status_code, response.headers, 200)
            if problem:
                response.failure(problem)
                login_failed(environment, f"Login as {username}: {problem}")
                return
            new_tokens.append(response.json()["access_token"])
    tokens.extend(new_tokens)  # all three, or none at all
    log.info("Logged in as %s", ", ".join(DEMO_USERS))


def login_failed(environment: Environment, reason: str) -> None:
    log.error("Login failed: %s", reason)
    environment.process_exit_code = 1  # kept by decide_exit_code() below


@events.quitting.add_listener
def decide_exit_code(environment: Environment, **_kwargs: object) -> None:
    """Passes the run (exit code 0) only if every failed request was a correct 429; see checks.exit_code."""
    if environment.process_exit_code is not None:  # already decided: the login failed
        return
    errors = list(environment.stats.errors.values())
    failures = [str(error.error) for error in errors]  # the messages given to response.failure()
    crashes = len(environment.runner.exceptions) if environment.runner is not None else 0
    environment.process_exit_code = exit_code(failures, crashes)

    rate_limited = sum(error.occurrences for error in errors if str(error.error) == RATE_LIMITED)
    if environment.process_exit_code == 0:
        log.info("PASSED: %d requests refused with a correct 429, no other failures", rate_limited)
    else:
        log.error("FAILED: failures other than a correct 429 (see the error report above), or the script crashed")
