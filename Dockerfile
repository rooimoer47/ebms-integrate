# Base images are pinned by digest so rebuilding a commit produces the same image.
# Refresh with: docker buildx imagetools inspect <tag>
FROM maven:3.9-eclipse-temurin-26@sha256:166ca19b6b5fe1e924ab2d66b64ba9854c739f16210b94bbe0074b036c5c7992 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
# .git is copied so /actuator/info can report the commit the image was built from.
COPY .git ./.git
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72

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
