# One image definition for all three services: docker build --build-arg SERVICE=<name> .
# SERVICE is wallet-service, bank-sim or notification-service.
#
# The build stage is the same for every SERVICE, so BuildKit runs Gradle once when compose builds all three.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
COPY services services
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon bootJar \
    && mkdir /out \
    && cp build/libs/wallet-platform-*-SNAPSHOT.jar /out/wallet-service.jar \
    && cp services/bank-sim/build/libs/bank-sim-*-SNAPSHOT.jar /out/bank-sim.jar \
    && cp services/notification-service/build/libs/notification-service-*-SNAPSHOT.jar /out/notification-service.jar

FROM eclipse-temurin:21-jre
ARG SERVICE
RUN test -n "$SERVICE" || (echo "--build-arg SERVICE is required" && false)
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /out/${SERVICE}.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
