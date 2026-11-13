FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY target/*.jar app.jar

EXPOSE 58761

ENTRYPOINT ["java", "-jar", "app.jar"]