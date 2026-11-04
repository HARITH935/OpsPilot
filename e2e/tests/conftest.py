from collections.abc import Iterator

import pytest

from stack import LOGS, Stack, StackError


@pytest.fixture(scope="session")
def stack() -> Iterator[Stack]:
    """The four services, started once for the whole run and stopped at the end, whatever happens."""
    services = Stack()
    try:
        services.check_ready_to_start()
    except StackError as e:
        pytest.exit(f"Can't run the end-to-end tests: {e}", returncode=2)
    try:
        services.start_all()
        yield services
    finally:
        services.stop_all()


def pytest_terminal_summary(terminalreporter: pytest.TerminalReporter) -> None:
    if terminalreporter.stats.get("failed") or terminalreporter.stats.get("error"):
        terminalreporter.write_line(f"The services' logs of this run: {LOGS}/")
