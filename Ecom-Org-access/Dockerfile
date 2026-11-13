FROM maven:3.9.9-eclipse-temurin-21 AS buildlify

WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=buildlify /workspace/target/*.jar app.jar

EXPOSE 53310

ENTRYPOINT ["java", "-jar", "app.jar"]
