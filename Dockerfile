# syntax=docker/dockerfile:1.7

# ---------- build ----------
# Pinned to BUILDPLATFORM on purpose. The output of `sbt stage` is JVM bytecode
# and shell scripts, all architecture-independent, so the compile runs ONCE on
# the native builder rather than under QEMU emulation per target arch. Only the
# runtime stage below is actually multi-arch.
FROM --platform=$BUILDPLATFORM sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_1.13.0_2.13.18 AS builder

WORKDIR /src

# Dependency resolution is the slow half and changes far less often than source,
# so resolve against the build definition alone first and let that layer cache.
COPY build.sbt version.sbt ./
COPY project/ project/
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt -batch update

COPY modules/ modules/
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    sbt -batch orchestration/stage

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-noble AS runtime

# The service holds no local state -- everything durable is in Postgres -- so it
# runs unprivileged with a read-only-friendly layout and no writable app dir.
RUN groupadd --system --gid 10001 demeter \
 && useradd --system --uid 10001 --gid demeter --home-dir /opt/demeter --shell /usr/sbin/nologin demeter

COPY --from=builder --chown=root:root /src/modules/orchestration/target/universal/stage /opt/demeter

# sbt-native-packager stages the launcher 0744 -- executable by its owner only.
# `docker run` happened not to care, but under a Kubernetes securityContext with
# runAsUser: 10001 the container dies on "permission denied" before the JVM ever
# starts. Make the two launchers world-executable; nothing else in here is meant
# to be run directly.
RUN chmod 0755 /opt/demeter/bin/demeter-orchestration /opt/demeter/bin/replay \
 && rm -f /opt/demeter/bin/*.bat

USER 10001:10001
WORKDIR /opt/demeter

# Every knob is an environment variable (see Main.scala loadConfig); the chart
# supplies them. Defaults here stay empty so a misconfigured deploy fails fast
# and loudly rather than silently polling the wrong postal code.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# No port is exposed by design: nothing binds a socket. The run report is
# written to the log, and alerts are pushed outward to Home Assistant.
ENTRYPOINT ["/opt/demeter/bin/demeter-orchestration"]
