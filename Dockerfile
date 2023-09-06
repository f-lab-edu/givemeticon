FROM adoptopenjdk:17-jdk-alpine
ARG JAR_FILENAME=*-SNAPSHOT.jar
COPY build/libs/${JAR_FILENAME} app.jar
RUN mkdir /temp-image
ENTRYPOINT ["java","-jar","/app.jar"]