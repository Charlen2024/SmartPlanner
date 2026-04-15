FROM docker.m.daocloud.io/library/maven:3.8.4-openjdk-17-slim AS build
WORKDIR /app

# 1. 首先复制父工程 pom.xml 和所有子模块的 pom.xml
COPY pom.xml .
COPY settings.xml /root/.m2/settings.xml
COPY common/pom.xml common/
COPY goal-service/pom.xml goal-service/
COPY schedule-engine/pom.xml schedule-engine/
COPY resource-search/pom.xml resource-search/
COPY punch-service/pom.xml punch-service/
COPY user-service/pom.xml user-service/
COPY gateway-service/pom.xml gateway-service/

# 2. 复制 common 和目标服务的源码
COPY common/src common/src
COPY schedule-engine/src schedule-engine/src

# 3. 执行构建
RUN mvn clean install -pl common -am -DskipTests -s /root/.m2/settings.xml
RUN mvn package -pl schedule-engine -am -DskipTests -s /root/.m2/settings.xml

FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/schedule-engine/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
