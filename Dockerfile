# Portal Monitor - server image. Needs Docker 23+ (BuildKit, for the cache mount below).
#
# Base images are pinned by digest: every build uses exactly the same JDK/JRE, and a build
# works from the local image cache even when Docker Hub is unreachable. To take security
# updates, look up the current digests (docker buildx imagetools inspect <image:tag>),
# update both FROM lines, test, commit.
#
# Build:   docker build -t portal-monitor:<git-sha> .        (deploy.sh does this)
# Runs exactly the production command:
#   java -Xms32m -Xmx192m -cp /app/portal-monitor.jar portalmonitor.App \
#        --mode server --config /run/portal-monitor/application.properties
#
# The configuration (with passwords) is NEVER baked in: it is mounted read-only at
# /run/portal-monitor/application.properties by docker-compose.yml.

# ---------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# The Maven repository lives in a BuildKit cache that survives between builds, so
# dependencies are downloaded once on the VPS, not on every deploy.
# Tests run before pushing (README "Local development"); they need a Docker CLI and
# optionally MySQL, neither of which exists inside this build container.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---------------------------------------------------------------- runtime stage
FROM eclipse-temurin:17-jre-jammy@sha256:aa6381384f8db99762fa7d0d3c2b5de58874e5f12c96df4c13a1455376ae0ed1

# Run as an unprivileged user with a fixed uid (matches the tmpfs owner in compose).
RUN groupadd --system --gid 10001 monitor  && useradd --system --uid 10001 --gid monitor --no-create-home --shell /usr/sbin/nologin monitor  && mkdir -p /run/portal-monitor && chown monitor:monitor /run/portal-monitor && chmod 700 /run/portal-monitor

WORKDIR /app
COPY --from=build /build/target/portal-monitor.jar /app/portal-monitor.jar
COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh

ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="portal-monitor" \
      org.opencontainers.image.revision="${GIT_SHA}"

USER monitor
EXPOSE 8085

# Readiness, not liveness: healthy only when MySQL answers and monitoring cycles complete.
# The probe is a tiny Java class (no curl in the image) with a small heap. start-period
# covers JVM start plus the first cycle. Lives in the image, so a rolled-back image
# keeps the healthcheck that matches its own code.
HEALTHCHECK --interval=30s --timeout=20s --start-period=90s --retries=3 \
  CMD ["java", "-Xmx24m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", \
       "-cp", "/app/portal-monitor.jar", "portalmonitor.HealthProbe", "http://127.0.0.1:8085/ready"]

# The entrypoint execs java, so java is PID 1's direct child under `init: true`,
# receives SIGTERM and runs the shutdown hook - a clean stop on every deploy.
# No arguments = server mode; arguments go to portalmonitor.App (e.g. --test-db ...).
ENTRYPOINT ["/app/entrypoint.sh"]
