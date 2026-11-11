FROM maven:3.9.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/var/mail-outbox && chown -R 10001:10001 /app
COPY --from=build /workspace/target/enterpriseos-api-0.1.0.jar /app/api.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/api.jar"]
