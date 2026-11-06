ARG JAVA_RUNTIME=eclipse-temurin:17-jre-jammy
FROM ${JAVA_RUNTIME}
WORKDIR /app
COPY target/event-platform-0.1.0.jar /app/event-platform.jar
RUN mkdir -p /app/runtime /tmp/xxl-job && chown -R 10001:10001 /app/runtime /tmp/xxl-job
ENV HOME=/app/runtime
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java","-Duser.home=/app/runtime","-Xms96m","-Xmx320m","-jar","/app/event-platform.jar"]
