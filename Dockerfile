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
# musl, not glibc, and that is the whole point.
#
# k3s 1.21 on the target NAS ships a 2021-era runc whose default seccomp profile
# denies clone3 with EPERM rather than ENOSYS. glibc 2.34+ (so any current
# Ubuntu base) calls clone3 and can only fall back on ENOSYS, so every JVM
# thread creation fails:
#
#   Failed to start thread "GC Thread#0" - pthread_create failed (EPERM)
#
# `java -version` then prints nothing, and the launcher's own detection reports
# "No java installations was detected" -- which sends you hunting for a missing
# JRE that is in fact right there. musl does not use clone3, so the JVM starts
# normally and the pod keeps seccompProfile: RuntimeDefault instead of having to
# drop a security control to accommodate an old kernel.
FROM eclipse-temurin:21-jre-alpine AS runtime

# The launcher sbt-native-packager generates is #!/usr/bin/env bash, and Alpine
# ships only BusyBox sh.
RUN apk add --no-cache bash

# The service holds no local state -- everything durable is in Postgres -- so it
# runs unprivileged with a read-only-friendly layout and no writable app dir.
RUN addgroup -S -g 10001 demeter \
 && adduser -S -u 10001 -G demeter -h /opt/demeter -s /sbin/nologin demeter

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
