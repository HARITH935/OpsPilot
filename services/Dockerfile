# One recipe for all four Java services. Build from services/ and pick the service:
#   docker build --build-arg SERVICE=order-service -t opspilot/order-service:dev .
# Three stages, each with one job: build the jar → split it into layers → a small run image (JRE + layers only).
# Only the last stage becomes the image; the JDK, Maven and the sources stay behind. Why these choices: ADR 0008.

# ── 1. build: Maven builds the jar ──────────────────────────────────────────────────────────────────────────────────
# --platform=$BUILDPLATFORM: run on the build machine's own chip. A jar is the same on every chip, so an
# amd64 + arm64 build compiles once and never runs Maven under (slow) emulation.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.13_9-jdk-noble AS build
ARG SERVICE
WORKDIR /src
# Every pom first: Maven reads all modules the parent lists, even when it builds only one
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY common-events/pom.xml common-events/
COPY common-messaging/pom.xml common-messaging/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY api-gateway/pom.xml api-gateway/
# Then only the code this image is made of: a change in another service leaves this build's cache valid
COPY common-events/src common-events/src
COPY common-messaging/src common-messaging/src
# ${SERVICE:?…}: stop with this message if no SERVICE was given (BuildKit checks COPY sources before any RUN)
COPY ["${SERVICE:?is required, e.g. --build-arg SERVICE=order-service}/src", "${SERVICE}/src"]
# -pl: this service, -am: plus the modules it uses. Tests already ran (./mvnw verify, CI); not again here.
# The cache mount keeps Maven's downloads (~/.m2) between builds on this machine; it is not part of any image.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl "$SERVICE" -am -DskipTests package \
 && cp "$SERVICE"/target/"$SERVICE"-*.jar /app.jar

# ── 2. layers: split the jar (still on the build chip) ────────────────────────────────────────────────────────────
# The jar's BOOT-INF/layers.idx says which file goes into which layer. Result: app.jar (only our classes) + lib/
FROM build AS layers
RUN java -Djarmode=tools -jar /app.jar extract --layers --destination /layers

# ── 3. run: what actually ships ─────────────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21.0.13_9-jre-noble
ARG SERVICE
# Links the image to the repo (GHCR shows it on the repo page) and says what is inside
LABEL org.opencontainers.image.source="https://github.com/HARITH935/OpsPilot" \
      org.opencontainers.image.title="opspilot-${SERVICE}" \
      org.opencontainers.image.description="OpsPilot ${SERVICE} (Spring Boot)"
# The user the service runs as: no password, no login shell, no home directory
RUN groupadd --system --gid 10001 opspilot \
 && useradd --system --uid 10001 --gid opspilot --no-create-home --shell /usr/sbin/nologin opspilot
WORKDIR /app
# Least often changed first: a new build of our code only replaces the last, small layer.
# The files stay owned by root, so the service can read its code but never change it.
COPY --from=layers /layers/dependencies/ ./
COPY --from=layers /layers/spring-boot-loader/ ./
COPY --from=layers /layers/snapshot-dependencies/ ./
COPY --from=layers /layers/application/ ./
# A number, not a name: Kubernetes (runAsNonRoot) can only check that a numeric user isn't root (0)
USER 10001:10001
# Heap up to 50% of the container's memory limit (the JVM's default is 25%); the rest is for Java's own memory
# outside the heap (classes, threads, buffers). More JVM options: env JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "app.jar"]
