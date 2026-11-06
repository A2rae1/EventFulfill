ARG JAVA_RUNTIME=eclipse-temurin:17-jre-jammy
FROM ${JAVA_RUNTIME}
WORKDIR /app
COPY target/event-gateway-0.1.0.jar /app/gateway.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-Xms64m","-Xmx192m","-jar","/app/gateway.jar"]
