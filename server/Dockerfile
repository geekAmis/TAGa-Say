# syntax=docker/dockerfile:1

FROM gradle:8.9-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/install/tagasay-server/ ./
EXPOSE 8080
ENV TAGASAY_PORT=8080
ENTRYPOINT ["/app/bin/tagasay-server"]
