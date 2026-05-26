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
COPY user-service/src user-service/src

RUN mvn clean install -pl common -am -DskipTests -s /root/.m2/settings.xml -Dhttps.protocols=TLSv1.2 -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true
RUN mvn package -pl user-service -am -DskipTests -s /root/.m2/settings.xml -Dhttps.protocols=TLSv1.2 -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true

FROM docker.1panel.live/library/eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/user-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
