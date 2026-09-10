# Base images are pinned by digest so rebuilding a commit produces the same image.
# Refresh with: docker buildx imagetools inspect <tag>
FROM maven:3.9-eclipse-temurin-21@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
# .git is copied so /actuator/info can report the commit the image was built from.
COPY .git ./.git
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-noble@sha256:d35199d74a3b2dff1bfb435d9adde4ef974e74a7d2a4fcb3079063c55926edb5

ARG VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.title="ebms-integrate" \
      org.opencontainers.image.description="ebMS 2.0 Message Service Handler" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/rooimoer47/ebms-integrate"

# Non-root, and no home or shell it does not need.
RUN useradd --system --uid 10001 --shell /usr/sbin/nologin --no-create-home ebms

WORKDIR /app
COPY --from=build /app/target/ebms-msh-*.jar app.jar

USER 10001:10001
EXPOSE 8080

# Runs with a read-only root filesystem. The JVM still needs somewhere to write,
# so give it a writable /tmp (in Kubernetes: an emptyDir mounted at /tmp).
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"

ENTRYPOINT ["java", "-jar", "app.jar"]
