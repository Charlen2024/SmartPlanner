

FROM docker.m.daocloud.io/library/maven:3.8.4-openjdk-17-slim AS build
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
COPY admin-server/pom.xml admin-server/

COPY common/src common/src
COPY admin-server/src admin-server/src

RUN mvn clean install -pl common -am -DskipTests -s /root/.m2/settings.xml -Dhttps.protocols=TLSv1.2 -Dmaven.wagon.http.retryHandler.count=5
RUN mvn package -pl admin-server -am -DskipTests -s /root/.m2/settings.xml -Dhttps.protocols=TLSv1.2 -Dmaven.wagon.http.retryHandler.count=5

FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/admin-server/target/*.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
