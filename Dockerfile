FROM eclipse-temurin:17-jdk-alpine
ARG JAR_FILENAME=*-SNAPSHOT.jar
COPY build/libs/${JAR_FILENAME} app.jar
RUN mkdir /temp-images
ENTRYPOINT ["java","-jar","/app.jar"]