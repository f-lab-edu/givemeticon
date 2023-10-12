FROM eclipse-temurin:17-jdk-alpine
COPY build/libs/*.jar app.jar
RUN mkdir /temp-images
ENTRYPOINT ["java","-jar","/app.jar", "--spring.profiles.active=production"]