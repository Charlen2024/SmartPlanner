FROM docker.1panel.live/library/maven:3.8.4-openjdk-17-slim AS build
WORKDIR /app

COPY pom.xml .
COPY settings.xml /root/.m2/settings.xml
COPY common/pom.xml common/
COPY goal-service/pom.xml goal-service/
COPY schedule-engine/pom.xml schedule-engine/
COPY resource-search/pom.xml resource-search/
COPY punch-service/pom.xml punch-service/
COPY user-service/pom.xml user-service/
COPY gateway-service/pom.xml gateway-service/

COPY common/src common/src
COPY resource-search/src resource-search/src

RUN mvn clean install -pl common -am -DskipTests -s /root/.m2/settings.xml
RUN mvn package -pl resource-search -am -DskipTests -s /root/.m2/settings.xml

FROM docker.1panel.live/library/eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/resource-search/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
