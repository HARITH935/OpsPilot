from checks import RATE_LIMITED, exit_code, problem_with

# The headers the gateway sends with a 429 (RateLimitFilter)
REFUSED = {"Retry-After": "1", "X-RateLimit-Limit": "10", "X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1"}


def test_the_expected_status_is_no_problem() -> None:
    assert problem_with(200, {}, expected=200) is None
    assert problem_with(202, {}, expected=202) is None


def test_another_success_status_is_still_a_problem() -> None:
    # The order API answers 202 Accepted: a 200 would mean something changed in the API
    assert problem_with(200, {}, expected=202) == "expected 202, got 200"


def test_a_server_error_is_a_problem() -> None:
    assert problem_with(500, {}, expected=200) == "expected 200, got 500"


def test_no_answer_at_all_is_a_problem() -> None:
    assert problem_with(0, {}, expected=200) == "no answer (connection failed or timed out)"


def test_a_correct_429_is_reported_as_rate_limited() -> None:
    assert problem_with(429, REFUSED, expected=200) == RATE_LIMITED
    assert problem_with(429, REFUSED | {"Retry-After": "39"}, expected=200) == RATE_LIMITED  # the login limit


def test_a_429_must_say_when_to_come_back() -> None:
    without = {k: v for k, v in REFUSED.items() if k != "Retry-After"}
    assert problem_with(429, without, expected=200) == "429 without a valid Retry-After"
    for wrong in ["", "0", "-1", "1.5", "soon"]:
        assert problem_with(429, REFUSED | {"Retry-After": wrong}, expected=200) == "429 without a valid Retry-After"


def test_a_429_must_show_the_budget_is_used_up() -> None:
    assert problem_with(429, REFUSED | {"X-RateLimit-Remaining": "3"}, expected=200) == (
        "429 but X-RateLimit-Remaining is not 0"
    )
    without = {k: v for k, v in REFUSED.items() if k != "X-RateLimit-Remaining"}
    assert problem_with(429, without, expected=200) == "429 but X-RateLimit-Remaining is not 0"


def test_a_run_without_failures_passes() -> None:
    assert exit_code([], exceptions=0) == 0


def test_a_run_with_only_correct_429s_passes() -> None:
    assert exit_code([RATE_LIMITED, RATE_LIMITED], exceptions=0) == 0


def test_any_other_failure_fails_the_run() -> None:
    assert exit_code([RATE_LIMITED, "expected 200, got 500"], exceptions=0) == 1
    assert exit_code(["429 without a valid Retry-After"], exceptions=0) == 1


def test_a_crash_in_the_script_fails_the_run() -> None:
    assert exit_code([], exceptions=1) == 1
