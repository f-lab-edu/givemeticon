FROM eclipse-temurin:17-jdk-alpine

ADD https://github.com/pinpoint-apm/pinpoint/releases/download/v2.5.3/pinpoint-agent-2.5.3.tar.gz /usr/local
RUN tar -zxvf /usr/local/pinpoint-agent-2.5.3.tar.gz -C /usr/local

# Update the Pinpoint configuration
RUN sed -i 's/profiler.transport.grpc.collector.ip=127.0.0.1/profiler.transport.grpc.collector.ip=223.130.143.33/g' /usr/local/pinpoint-agent-2.5.3/pinpoint-root.config
RUN sed -i 's/profiler.collector.ip=127.0.0.1/profiler.collector.ip=223.130.143.33/g' /usr/local/pinpoint-agent-2.5.3/pinpoint-root.config
COPY build/libs/*.jar app.jar
RUN mkdir /temp-images
# root로 사용자 설정
USER root
ENTRYPOINT ["java", \
"-javaagent:/usr/local/pinpoint-agent-2.5.3/pinpoint-bootstrap-2.5.3.jar", \
"-Dpinpoint.applicationName=givemeticon_back", \
"-Dpinpoint.config=/usr/local/pinpoint-agent-2.5.3/pinpoint-root.config", \
"-Dspring.profiles.active=production", \
"-jar", "/app.jar"]
