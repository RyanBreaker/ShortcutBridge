FROM eclipse-temurin:25.0.5_8-jdk-noble AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /ui-chime-vol
COPY kotlin module.yaml ./
COPY src src
COPY test test
RUN KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin test \
    && KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin package -f executable-jar

FROM eclipse-temurin:25.0.5_8-jre-noble

RUN useradd --system --uid 10001 app
USER 10001
WORKDIR /app
COPY --from=build /ui-chime-vol/build/tasks/_ui-chime-vol_executableJarJvm/ui-chime-vol-jvm-executable.jar service.jar

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
