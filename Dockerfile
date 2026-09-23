# Build and run the application as a container.
#
# Two stages so the JDK, the Maven repository and the source tree stay out of
# the runtime image: the builder is ~800MB, what ships is a JRE plus one jar.
#
# Both base images carry a tag AND a digest, for the reason CLAUDE.md §5
# records about Testcontainers images: a developer machine builds from its
# local cache, so an image that has been retagged or deleted upstream keeps
# working locally and fails only on a clean build or on CI. A tag alone does
# not prevent that - even a version tag like 21-jre-jammy is republished - so
# the digest is what actually pins it. The tag stays for readability.
#
# To update: pull the tag, read `docker image inspect <tag> --format
# '{{index .RepoDigests 0}}'`, and paste the new digest here deliberately.
#
# Why a Dockerfile rather than Jib or buildpacks, since both would remove this
# file entirely:
#
#  - Jib needs no Docker daemon, layers dependencies separately from classes
#    so a code-only change pushes kilobytes instead of a whole fat jar, and
#    pushes straight to a registry. For frequent deploys to ECR that is the
#    better tool, and the honest reason it was not chosen is that this
#    Dockerfile was already verified end to end - fonts, memory ceiling,
#    non-root, graceful shutdown - and swapping build systems mid-deployment
#    would mean re-proving all of it to gain faster incremental pushes on
#    something deployed occasionally. The font requirement below was
#    originally cited as a reason against Jib; that turned out to be wrong,
#    since the base image already carries them.
#  - `spring-boot:build-image` needs no new dependency at all, and Paketo's
#    memory calculator is better than the MaxRAMPercentage guess further down,
#    because it accounts for thread and class count rather than total RAM
#    alone. It costs a larger image and a slower build.
#
# Neither is ruled out. If deploys become frequent, Jib is the one to revisit.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21@sha256:c2a2c58516d160f43b50f12baa427ca86989e0bc942609e04aff61da5d9a7d74 AS build
WORKDIR /build

# Dependencies first, as their own layer: pom.xml changes far less often than
# src/, so an ordinary code change reuses the downloaded repository instead of
# fetching it again.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are not run here. They need Docker (Testcontainers starts Postgres,
# MinIO, Mailpit, ElasticMQ and stripe-mock), which would mean docker-in-docker
# inside a build container. CI runs the full suite on every push; this stage
# packages what CI has already verified.
RUN mvn -B -q -DskipTests package

# ---------- runtime ----------
# Ubuntu 22.04, OpenJDK 21.0.12 LTS.
#
# This base is chosen partly for its fonts. openhtmltopdf rasterises the
# invoice PDF through java.awt, which needs fontconfig and at least one real
# font family; without them the failure is not a missing-class error but a PDF
# with wrong metrics or no text. This image already ships fontconfig and
# DejaVu - `fc-list` returns 6 - so nothing needs installing, and the
# `sans-serif` the template asks for resolves to DejaVu Sans.
#
# An earlier version of this file apt-got fontconfig and fonts-dejavu-core on
# top. It was verifiably a no-op: identical font count with and without. Check
# `fc-list` before adding it back if the base image is ever changed to one
# that does not carry them - a slim or distroless base generally will not.
FROM eclipse-temurin:21-jre-jammy@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f AS runtime

# Not root. Nothing here needs it, and the container has AWS credentials from
# an instance role in its environment.
RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /app

COPY --from=build --chown=app:app /build/target/subscription-hub-*.jar app.jar

EXPOSE 8080

# A percentage rather than a fixed -Xmx, so the same image is correct on a
# 1 GiB free-tier instance and on anything larger. The JVM reads the cgroup
# limit, so this tracks whatever the container is actually given.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.awt.headless=true"

# Serial GC on a single small instance: the throughput collectors reserve
# memory and threads that a 1 GiB box does not have to spare, and this
# workload is one request at a time.

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
