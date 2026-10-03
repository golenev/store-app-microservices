FROM maven:3.9.9-eclipse-temurin-21 AS build
ARG SERVICE_MODULE
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl ${SERVICE_MODULE} -am -DskipTests package && \
    cp ${SERVICE_MODULE}/target/${SERVICE_MODULE}-0.0.1-SNAPSHOT.jar /application.jar

FROM eclipse-temurin:21-jre-jammy
RUN useradd --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /application.jar /app/application.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
