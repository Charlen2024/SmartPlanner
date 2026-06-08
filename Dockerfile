ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.8.4-openjdk-17-slim
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-alpine

FROM ${MAVEN_IMAGE} AS build
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

ARG SERVICE_NAME
COPY ${SERVICE_NAME}/src ${SERVICE_NAME}/src

ARG MAVEN_OPTS
RUN mvn clean install -pl common -am -DskipTests -s /root/.m2/settings.xml ${MAVEN_OPTS}
RUN mvn package -pl ${SERVICE_NAME} -am -DskipTests -s /root/.m2/settings.xml ${MAVEN_OPTS}

FROM ${JRE_IMAGE}
WORKDIR /app

ARG SERVICE_NAME
ARG SERVICE_PORT
COPY --from=build /app/${SERVICE_NAME}/target/*.jar app.jar
EXPOSE ${SERVICE_PORT}

ARG JVM_OPTS
ENTRYPOINT java ${JVM_OPTS} -jar app.jar
