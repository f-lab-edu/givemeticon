FROM eclipse-temurin:17-jdk-alpine
COPY build/libs/*.jar app.jar
RUN mkdir /temp-images
# root로 사용자 설정
USER root
ENTRYPOINT ["java", \
"-javaagent:/root/pinpoint-agent/pinpoint-agent-2.5.3/pinpoint-bootstrap-2.5.3.jar", \
"-Dpinpoint.applicationName=givemeticon_back", \
"-Dpinpoint.config=/root/pinpoint-agent/pinpoint-agent-2.5.3/pinpoint-root.config", \
"-Dspring.profiles.active=production", \
"-jar", "/app.jar"]
