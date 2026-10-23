# App image for docker-compose.load.yml. Build context: coupon-lab/.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN ./gradlew bootJar --no-daemon --console=plain \
    && cp build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app.jar
EXPOSE 8080
# Fixed heap: the load results are only comparable across stages with the same JVM settings.
ENTRYPOINT ["java", "-Xms1g", "-Xmx1g", "-jar", "/app.jar"]
