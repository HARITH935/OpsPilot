# loadgen

[Locust](https://locust.io) traffic for the demo shop, sent through the API gateway on :8080. Today it is the
**rate-limit load test** (step 2.9b): it shows the gateway answering 429 once a caller crosses its budget, and
nothing else going wrong. In Phase 5 the same shoppers become the 24/7 traffic of the public demo.

**You need:** [uv](https://docs.astral.sh/uv/) (it fetches Python 3.11 and the packages by itself), and the local
stack with the three services and the gateway running ([Run locally](../README.md#run-locally)).

```bash
cd loadgen
uv sync                  # once: Locust (+ pytest and ruff for development) into loadgen/.venv
```

## The shoppers
Each shopper browses the catalogue, looks at products and now and then places an order and checks it again
(weights 5 : 5 : 1 : 2). Between two of those it waits a random 0.5–1.5 s, so it does **about one request per
second**: 40 shoppers ≈ 40 requests per second.

| Kind | Sends | Counted by the gateway as | Budget per route |
|---|---|---|---|
| `AnonymousShopper` | no token | its IP address: **all of them share one**, since they run on this machine | 10 per s |
| `LoggedInShopper` | a token (`Authorization: Bearer …`) | the token's `sub`: the three demo users, taken in turn | 20 per s each |

The logged-in shoppers **log in once** when the test starts (viewer, engineer, admin) and share those three tokens,
as a real client keeps its token instead of logging in again. So a run uses 3 of the 10 logins per minute; after 3
quick runs in a row, wait a minute. Locust starts both kinds half and half.

## Run it
```bash
# Gentle: 4 shoppers for 30 s, under every limit, so no 429s
DEMO_USERS_PASSWORD=$(grep '^DEMO_USERS_PASSWORD=' ../deploy/docker/.env | cut -d= -f2-) \
  uv run locust --headless -u 4 -r 4 -t 30s --only-summary

# Spike: 40 shoppers for 30 s; the 20 anonymous ones together go over their 10 per second
DEMO_USERS_PASSWORD=$(grep '^DEMO_USERS_PASSWORD=' ../deploy/docker/.env | cut -d= -f2-) \
  uv run locust --headless -u 40 -r 40 -t 30s --only-summary

# Only anonymous shoppers (no password needed)
uv run locust --headless -u 20 -r 20 -t 30s --only-summary AnonymousShopper
```
`-u` shoppers, `-r` how many start per second, `-t` how long. The gateway is `http://localhost:8080` unless you add
`--host <url>`. Without `--headless` Locust opens its web page on http://localhost:8089 with live charts instead.

**The gateway's own count** of refused requests, before and after a run:
```bash
curl -s localhost:8080/actuator/prometheus | grep '^gateway_ratelimit_rejected_total'
```
It appears with the first 429; until then the command prints nothing.

## Reading the results
Every answer is checked (`checks.py`). The expected status (200, or 202 for a new order) is a success; anything else
is a failure with a reason:

| Failure | Means |
|---|---|
| `429 Too Many Requests (with Retry-After)` | the rate limiter doing its job correctly: the 429 says when to come back (`Retry-After`, ≥ 1 s) and shows the budget is used up (`X-RateLimit-Remaining: 0`) |
| `429 without a valid Retry-After`, `429 but X-RateLimit-Remaining is not 0` | a broken 429 |
| `expected 200, got 500` (or any other status) | something is wrong behind the gateway |
| `no answer (connection failed or timed out)` | the gateway is down or too slow |

At the end the script prints its verdict: **PASSED** (exit code 0) if every failure was a correct 429, **FAILED**
(exit code 1) otherwise. If the login fails (password not set or wrong, or the login limit used up), the run stops
at once with exit code 1. Locust then prints a traceback ending in `StopTest`; that is how it ends a run early.

**From the real run (2026-11-03, MacBook Air M2):**

| | Gentle (4 shoppers) | Spike (40 shoppers) |
|---|---|---|
| Requests in 30 s | 113 (3.8 per s) | 1128 (37.8 per s) |
| 429s | 0 | 191, all correct: `GET /api/products` 93, `GET /api/products/{sku}` 98 |
| Other failures | 0 | 0 |
| `gateway_ratelimit_rejected_total` | unchanged | +191, all `{key_type="ip",route="products"}` |
| Median answer | 20 ms | 8 ms (a 429 is quick: it never reaches a service) |
| Verdict | PASSED | PASSED |

What it shows:
- Only the **anonymous** shoppers were refused: together they sent about 15 product requests per second against a
  shared budget of 10. The logged-in shoppers sent about as many, but spread over three users with 20 each.
- Only the **products** route was refused: orders had its own budget and stayed under it.
- Locust's count of 429s and the gateway's counter agree exactly.

Read the numbers from the summary Locust prints. The `--csv` files are written once per second, so they miss the
last second of the run (1103 requests there instead of 1128).

**Why the wait is random:** with a fixed beat (one request exactly every second) all shoppers fire at the same
moment. When one beat comes a little under 1 s after the last, the last is still inside the sliding window ("at most
10 in any 1-second stretch"), so most of that beat is refused. Measured with 20 anonymous shoppers: about 6 product
requests per second got through on a fixed beat, about 9 with random waits. Real shoppers don't move in step,
and a client that retries should add randomness too (step 2.11, retries with jitter).

**Good to know:** orders placed here use up stock of SKU-1 … SKU-9 in your local database (SKU-10 is left alone).
Once a product runs out, its orders are cancelled (`OUT_OF_STOCK`), which is fine for this test.
`docker compose … down -v` starts the stock over.

## Develop
```bash
uv run pytest -q         # the answer and verdict rules in checks.py
uv run ruff check .      # lint
uv run ruff format .     # format
```
`locustfile.py` describes the shoppers. `checks.py` holds the rules, apart from Locust, so the tests can import
them without starting it.
