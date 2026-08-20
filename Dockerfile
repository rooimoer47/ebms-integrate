FROM maven:3-eclipse-temurin-24 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /app/target/ebms-msh-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
