"""Starts and stops the four Java services for the end-to-end tests.

They run as the same jars as in the root README ("Run locally"), against the Postgres, Kafka and Redis containers of
deploy/docker. Passwords are read from deploy/docker/.env and handed to the services, never printed. Every service
writes its log to e2e/logs/<service>.log (new on every start), so a failing test can be looked into.
"""

import os
import re
import socket
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ENV_FILE = REPO / "deploy" / "docker" / ".env"
LOGS = Path(__file__).resolve().parent / "logs"
CONTAINERS = ("opspilot-postgres", "opspilot-kafka", "opspilot-redis")
ORDER_PORT = int(os.environ.get("E2E_ORDER_PORT", "18081"))  # not 8081: often taken by other dev tools (e.g. Expo)


class StackError(RuntimeError):
    """Something outside the tests is not ready: no Docker stack, no jar, a port in use, the wrong Java."""


@dataclass
class Service:
    name: str
    port: int
    heap: str
    env: dict[str, str]  # its settings; overrides given to start() win
    kafka_listeners: int  # how many Kafka listeners must get their partitions before it counts as ready
    process: subprocess.Popen | None = field(default=None, repr=False)

    @property
    def jar(self) -> Path:
        return REPO / "services" / self.name / "target" / f"{self.name}-0.0.1-SNAPSHOT.jar"

    @property
    def log_path(self) -> Path:
        return LOGS / f"{self.name}.log"

    def start(self, java: str, overrides: dict[str, str] | None = None) -> None:
        LOGS.mkdir(exist_ok=True)
        env = os.environ | self.env | (overrides or {})
        with self.log_path.open("w") as log:  # the child keeps its own copy of the file handle
            self.process = subprocess.Popen(
                [java, f"-Xmx{self.heap}", "-jar", str(self.jar)],
                env=env,
                cwd=REPO,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        self._wait_until_ready()

    def stop(self) -> None:
        """A normal shutdown (SIGTERM), so Kafka consumers leave their group at once: no rebalance stall."""
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.process = None

    def log(self) -> str:
        return self.log_path.read_text(errors="replace") if self.log_path.exists() else ""

    def wait_for_log(self, pattern: str, after: int = 0, timeout: float = 15) -> str:
        """The first log line matching the regex `pattern`, looking only past character `after` of the log."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            match = re.search(pattern, self.log()[after:])
            if match:
                return match.group(0)
            time.sleep(0.2)
        raise AssertionError(f"{self.name}: no log line matching {pattern!r} within {timeout} s (see {self.log_path})")

    def _wait_until_ready(self, timeout: float = 90) -> None:
        url = f"http://localhost:{self.port}/actuator/health/readiness"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise StackError(f"{self.name} stopped while starting (see {self.log_path})")
            try:
                with urllib.request.urlopen(url, timeout=2) as answer:
                    if answer.status == 200:
                        break
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                pass
            time.sleep(0.5)
        else:
            raise StackError(f"{self.name} not ready within {timeout} s (see {self.log_path})")
        # Ready for HTTP is not enough: an event sent before its Kafka listeners have their partitions would wait
        while len(re.findall(r"partitions assigned: \[\S", self.log())) < self.kafka_listeners:
            if time.monotonic() > deadline:
                raise StackError(f"{self.name}: Kafka listeners got no partitions within {timeout} s")
            time.sleep(0.2)


class Stack:
    """The four services, as the tests need them."""

    def __init__(self) -> None:
        secrets = read_env_file()
        self.java = find_java_21()
        redis = {"REDIS_PASSWORD": secrets["REDIS_PASSWORD"]}
        self.services = {
            "order": Service(
                "order-service",
                ORDER_PORT,
                "256m",
                kafka_listeners=2,
                env={"SERVER_PORT": str(ORDER_PORT), "DB_PASSWORD": secrets["ORDERS_DB_PASSWORD"]},
            ),
            "inventory": Service(
                "inventory-service",
                8083,
                "256m",
                kafka_listeners=2,
                env={"DB_PASSWORD": secrets["INVENTORY_DB_PASSWORD"], **redis},
            ),
            "payment": Service(
                "payment-service",
                8082,
                "256m",
                kafka_listeners=2,
                env={
                    "DB_PASSWORD": secrets["PAYMENTS_DB_PASSWORD"],
                    # A healthy provider, unless a test restarts it with other values
                    "PAYMENT_FAILURE_RATE": "0.0",
                    "PAYMENT_LATENCY_MS": "100",
                },
            ),
            "gateway": Service(
                "api-gateway",
                8080,
                "128m",
                kafka_listeners=0,
                env={
                    "ORDER_SERVICE_URL": f"http://localhost:{ORDER_PORT}",
                    "DEMO_USERS_ENABLED": "true",
                    "DEMO_USERS_PASSWORD": secrets["DEMO_USERS_PASSWORD"],
                    **redis,
                    "RATE_LIMIT_ENABLED": "true",
                    "GATEWAY_CIRCUIT_OPEN_FOR": "5s",
                },
            ),  # an open circuit tests again after 5 s, not 30: shorter tests
        }

    def __getitem__(self, name: str) -> Service:
        return self.services[name]

    def check_ready_to_start(self) -> None:
        for container in CONTAINERS:
            health = subprocess.run(
                ["docker", "inspect", "-f", "{{.State.Health.Status}}", container], capture_output=True, text=True
            ).stdout.strip()
            if health != "healthy":
                raise StackError(
                    f"Container {container} is {health or 'missing'}: start the stack first "
                    "(docker compose -f deploy/docker/docker-compose.yml up -d)"
                )
        for service in self.services.values():
            if not service.jar.exists():
                raise StackError(f"{service.jar} is missing: build first (cd services && ./mvnw -DskipTests package)")
            if port_in_use(service.port):
                raise StackError(f"Port {service.port} is in use: stop whatever runs there ({service.name}?) first")

    def start_all(self) -> None:
        for service in self.services.values():  # in this order: the gateway last, the services it calls first
            service.start(self.java)

    def restart(self, name: str, **overrides: str) -> None:
        """Restarts one service, with settings changed for this start only (e.g. PAYMENT_FAILURE_RATE="1.0")."""
        self.services[name].stop()
        self.services[name].start(self.java, overrides)

    def stop(self, name: str) -> None:
        self.services[name].stop()

    def start(self, name: str) -> None:
        self.services[name].start(self.java)

    def stop_all(self) -> None:
        for service in reversed(self.services.values()):
            service.stop()


def read_env_file() -> dict[str, str]:
    if not ENV_FILE.exists():
        raise StackError(f"{ENV_FILE} is missing: cp deploy/docker/.env.example deploy/docker/.env and fill it in")
    values = {}
    for line in ENV_FILE.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def find_java_21() -> str:
    """$JAVA_HOME/bin/java if JAVA_HOME is set, else `java` from PATH; it must be Java 21 or newer."""
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if "JAVA_HOME" in os.environ else "java"
    try:
        version = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
    except FileNotFoundError as e:
        raise StackError(f"No Java found ({java}): install JDK 21 or set JAVA_HOME") from e
    match = re.search(r'version "(\d+)', version)
    if not match or int(match.group(1)) < 21:
        raise StackError(
            f"{java} is not Java 21+ ({version.splitlines()[0] if version else '?'}): "
            "set JAVA_HOME to a JDK 21, e.g. JAVA_HOME=/opt/homebrew/opt/openjdk@21"
        )
    return java


def port_in_use(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("localhost", port)) == 0
