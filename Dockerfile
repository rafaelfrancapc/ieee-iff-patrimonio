FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN mkdir /app/data && chown 10001:10001 /app/data
COPY --from=build /build/target/patrimonio-0.0.1-SNAPSHOT.jar /app/patrimonio.jar
USER 10001:10001
ENV BIND_ADDRESS=0.0.0.0
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/patrimonio.jar"]
