"""The load test's rules: when is an answer from the gateway right, and when has the whole run passed.

Kept apart from locustfile.py so pytest can import them without starting Locust.
"""

from collections.abc import Iterable, Mapping

# The one failure that is not a problem: the rate limiter refusing a request the way it should
RATE_LIMITED = "429 Too Many Requests (with Retry-After)"


def problem_with(status: int, headers: Mapping[str, str], expected: int) -> str | None:
    """What is wrong with an answer, or None if it is the expected one.

    A 429 is the gateway saying "too many requests". It still counts as a failed request in Locust (the shopper
    didn't get what it asked for), but a correct one is reported as RATE_LIMITED, so the run can accept it.
    A 429 is correct only if it says when to come back (Retry-After, whole seconds, at least 1) and shows that
    nothing is left of the budget (X-RateLimit-Remaining: 0).
    """
    if status == expected:
        return None
    if status == 0:  # how Locust reports a request that got no answer at all
        return "no answer (connection failed or timed out)"
    if status != 429:
        return f"expected {expected}, got {status}"
    retry_after = headers.get("Retry-After", "")
    if not retry_after.isdigit() or int(retry_after) < 1:
        return "429 without a valid Retry-After"
    if headers.get("X-RateLimit-Remaining") != "0":
        return "429 but X-RateLimit-Remaining is not 0"
    return RATE_LIMITED


def exit_code(failures: Iterable[str], exceptions: int) -> int:
    """0 (passed) if every failed request was a correct 429 and the script itself never crashed, else 1."""
    if exceptions > 0:
        return 1
    return 0 if all(failure == RATE_LIMITED for failure in failures) else 1
