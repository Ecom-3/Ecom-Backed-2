FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 58088
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
