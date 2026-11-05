"""Starts and stops the shop for the end-to-end tests, with Docker Compose.

The four services run as containers from the same images as the whole shop in deploy/docker (profile "apps",
step 3.2), built from the current code by `docker compose up --build`. Postgres, Kafka and Redis are started too if
they aren't running yet. Compose reads the passwords from deploy/docker/.env itself: this file never sees them.
At the end the four containers' logs are saved to e2e/logs/<service>.log and the containers are removed;
Postgres, Kafka and Redis keep running.
"""

import os
import re
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
COMPOSE_FILE = REPO / "deploy" / "docker" / "docker-compose.yml"
ENV_FILE = REPO / "deploy" / "docker" / ".env"
LOGS = Path(__file__).resolve().parent / "logs"
# Settings for the whole run, so a value left in your shell can't change the tests. A test changes more for one
# service with restart(), e.g. PAYMENT_FAILURE_RATE="1.0".
SETTINGS = {
    "PAYMENT_FAILURE_RATE": "0.0",  # a healthy payment provider
    "PAYMENT_LATENCY_MS": "100",
    "GATEWAY_CIRCUIT_OPEN_FOR": "5s",  # an open circuit tests again after 5 s, not 30: shorter tests
}


class StackError(RuntimeError):
    """Something outside the tests is not ready: Docker not running, no .env, a port in use, a container unhealthy."""


@dataclass
class Service:
    name: str  # the service in docker-compose.yml, e.g. "payment-service"
    port: int  # where it answers on this machine
    kafka_listeners: int  # how many Kafka listeners must get their partitions before it counts as ready

    @property
    def container(self) -> str:
        return f"opspilot-{self.name}"

    def log(self) -> str:
        """Everything this container has logged. Every start makes a new container, so: this start only."""
        result = subprocess.run(
            ["docker", "logs", self.container],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,  # one stream, in order
            text=True,
            errors="replace",
        )
        return result.stdout if result.returncode == 0 else ""

    def wait_for_log(self, pattern: str, after: int = 0, timeout: float = 15) -> str:
        """The first log line matching the regex `pattern`, looking only past character `after` of the log."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            match = re.search(pattern, self.log()[after:])
            if match:
                return match.group(0)
            time.sleep(0.2)
        raise AssertionError(f"{self.name}: no log line matching {pattern!r} within {timeout} s")

    def wait_for_kafka(self, timeout: float = 90) -> None:
        """Healthy means ready for HTTP. An event sent before the Kafka listeners have their partitions would wait."""
        deadline = time.monotonic() + timeout
        while len(re.findall(r"partitions assigned: \[\S", self.log())) < self.kafka_listeners:
            if time.monotonic() > deadline:
                raise StackError(f"{self.name}: Kafka listeners got no partitions within {timeout} s")
            time.sleep(0.2)

    def save_log(self) -> None:
        LOGS.mkdir(exist_ok=True)
        (LOGS / f"{self.name}.log").write_text(self.log())


class Stack:
    """The four services, as the tests need them."""

    def __init__(self) -> None:
        self.services = {
            "order": Service("order-service", 18081, kafka_listeners=2),
            "inventory": Service("inventory-service", 8083, kafka_listeners=2),
            "payment": Service("payment-service", 8082, kafka_listeners=2),
            "gateway": Service("api-gateway", 8080, kafka_listeners=0),
        }

    def __getitem__(self, name: str) -> Service:
        return self.services[name]

    def check_ready_to_start(self) -> None:
        if subprocess.run(["docker", "info"], capture_output=True).returncode != 0:
            raise StackError("Docker isn't running: start Docker Desktop first")
        if not ENV_FILE.exists():
            raise StackError(f"{ENV_FILE} is missing: cp deploy/docker/.env.example deploy/docker/.env and fill it in")
        for service in self.services.values():
            if port_in_use(service.port):
                raise StackError(
                    f"Port {service.port} is in use: stop whatever runs there first (your own {service.name}, or "
                    "the shop: docker compose -f deploy/docker/docker-compose.yml --profile apps down)"
                )

    def start_all(self) -> None:
        """Builds the images from the current code and starts the four (and Postgres, Kafka, Redis if needed)."""
        names = [s.name for s in self.services.values()]
        # --force-recreate: new containers, so no log from an earlier run; --wait: until all are healthy
        compose("up", "--detach", "--build", "--force-recreate", "--wait", "--wait-timeout", "180", *names)
        for service in self.services.values():
            service.wait_for_kafka()

    def restart(self, name: str, **settings: str) -> None:
        """Starts one service in a new container, with settings changed for this start only."""
        self.start(name, **settings)

    def stop(self, name: str) -> None:
        compose("stop", self.services[name].name)

    def start(self, name: str, **settings: str) -> None:
        service = self.services[name]
        # --no-deps: leave everything else alone (and don't re-run kafka-init, ~10 s)
        compose(
            "up",
            "--detach",
            "--no-deps",
            "--force-recreate",
            "--wait",
            "--wait-timeout",
            "120",
            service.name,
            settings=settings,
        )
        service.wait_for_kafka()

    def stop_all(self) -> None:
        for service in self.services.values():
            service.save_log()
        compose("rm", "--stop", "--force", *[s.name for s in self.services.values()])


def compose(*args: str, settings: dict[str, str] | None = None) -> None:
    """Runs `docker compose` on the shop's file, profile apps, with the run's settings (+ `settings`)."""
    command = ["docker", "compose", "-f", str(COMPOSE_FILE), "--profile", "apps", *args]
    env = os.environ | SETTINGS | (settings or {})
    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        last_lines = "\n".join((result.stdout + result.stderr).strip().splitlines()[-15:])
        raise StackError(f"docker compose {' '.join(args)} failed:\n{last_lines}")


def port_in_use(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("localhost", port)) == 0
