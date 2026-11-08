# ADR 0008 — Phase 3: one Dockerfile for all services, the jar built inside Docker, Temurin 21 base images

- **Status:** Accepted
- **Date:** 2026-11-04

## Context
Phase 3 packs the four Java services into container images. Later phases run those images unchanged: in `docker
compose` (3.2), in CI (3.4/3.5), on kind (Phase 4) and on an arm64 cloud VM (Phase 5). The architecture asks for
multi-stage, non-root images with layered jars, built for amd64 and arm64 (B8, B9). The four services are built the
same way: one Maven parent, Spring Boot 4.1 jars, Java 21. The development machine is an arm64 Mac (8 GB);
GitHub's standard runners are amd64.

## Decision
1. **One shared `services/Dockerfile`** for all four services, chosen with `--build-arg SERVICE=<module>`. A missing
   `SERVICE` stops the build with a clear message.
2. **The jar is built inside Docker** (stage `build`: JDK + the Maven wrapper, `-pl <service> -am -DskipTests package`),
   not copied from the host. Tests are not run there: `./mvnw verify` and CI run them. Maven's downloads are kept
   between local builds by a BuildKit cache mount (`~/.m2`), which is never part of an image.
   Each build copies every `pom.xml` (Maven reads all modules) but only the `src/` of the service and the two common
   modules, so a change in another service doesn't invalidate a build.
3. **The build runs on the build machine's own chip** (`FROM --platform=$BUILDPLATFORM`). A jar is the same on every
   chip, so a two-chip build compiles once; only the final stage is made per chip.
4. **The jar is split into Spring Boot's layers** (`java -Djarmode=tools … extract --layers`) and copied least-often-
   changed first: libraries, loader, snapshot libraries, application (our classes plus `common-*`, ~100 kB).
5. **Base images `eclipse-temurin:21.0.13_9-jdk-noble` (build) and `21.0.13_9-jre-noble` (run)**, pinned to the exact
   version. Ubuntu 24.04 based, made for amd64 and arm64.
6. **Non-root:** a system user `opspilot` with the numeric id `10001` (`USER 10001:10001`); the code stays owned by root,
   so the service can read but not change it. The image runs with a read-only root file system when `/tmp` is writable.
7. **Heap: `-XX:MaxRAMPercentage=50`** of the container's memory limit; the other half is for Java's memory outside the
   heap (classes, threads, buffers). More options via `JAVA_TOOL_OPTIONS`.

## Alternatives considered
| Option | Why not chosen |
|---|---|
| A Dockerfile per service | Four identical files to keep in step. A service that one day needs something special can still get its own. |
| Copy the jar built on the host (`./mvnw package`, then `COPY target/*.jar`) | Faster in CI, where the jar already exists, but the image would depend on whatever JDK and files the host has, and a forgotten `package` ships an old jar. Building inside Docker gives the same result everywhere. |
| Spring Boot's Buildpacks (`./mvnw spring-boot:build-image`) | No Dockerfile to write, but the image recipe is hidden; a Dockerfile is what Phase 4 and the interviews need to be explained line by line. |
| Distroless Java (`gcr.io/distroless/java21`) | Smaller, no shell, fewer packages to scan. But no shell or `curl` for looking inside or for compose health checks, which matters while learning. Can be revisited after Trivy (3.5) shows what Ubuntu costs. |
| Alpine-based JRE | Smaller, but musl instead of glibc; native libraries in the dependencies (Snappy, zstd, LZ4 for Kafka) are less tested there. |
| Build every chip under emulation (no `$BUILDPLATFORM`) | Maven under QEMU takes many times longer, for a byte-identical jar. |
| A user name (`USER opspilot`) | Works for Docker, but Kubernetes' `runAsNonRoot` can only verify a numeric user. |

## Consequences
- Build from `services/`: `docker build --build-arg SERVICE=order-service -t opspilot/order-service:dev .`
- Measured on 2026-11-04 (MacBook Air M2): first build 61 s (Maven downloads), then 7–21 s; a change to one Java line
  replaces only the last layer (102 kB) of the 172 MB compressed image; an amd64 image builds on the Mac in 15 s with
  the Maven step reused.
- With a 512 MB limit the JVM picks the Serial garbage collector (it treats a container under ~2 GB or with one CPU
  as small); fine at this size. Memory in use: 236–329 MB per service.
- The cache mount helps only locally. In CI the build starts empty; how to cache there is decided in step 3.5.
- In Kubernetes (Phase 4) the pods set `runAsNonRoot`, `readOnlyRootFilesystem: true` and a writable `emptyDir` on `/tmp`.
- *(Updated in step 3.5a.)* The run stage also installs Ubuntu's security updates at build time (`apt-get upgrade`,
  package lists deleted afterwards, +10 MB): Trivy in CI fails on HIGH / CRITICAL holes, and the first scan found one
  in `apt` that the base image didn't have fixed yet. The price: two builds of the same commit on different days can
  contain different Ubuntu package versions. The `sha-…` tag names the code, not the exact packages; the registry
  digest names the exact image.
