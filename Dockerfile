FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Resolve dependencies first so this layer is cached separately from source changes
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /app/target/ebms-msh-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
