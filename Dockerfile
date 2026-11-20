FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
RUN groupadd --system relay && useradd --system --gid relay relay && mkdir -p /data && chown relay:relay /data
WORKDIR /app
COPY --from=build /app/target/relay-transfer-1.0.0.jar app.jar
USER relay
ENV DATA_DIRECTORY=/data
EXPOSE 8082
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]
