# syntax=docker/dockerfile:1
# manage.py resolves these upstream tags to digests before a release build.
ARG MAVEN_IMAGE=maven:3.10.0-eclipse-temurin-17
ARG JAVA_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"
COPY tianmu-agent/pom.xml ./pom.xml
COPY tianmu-agent/src ./src
COPY deploy/HealthProbe.java /probe/HealthProbe.java
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && javac --release 17 -d /probe/classes /probe/HealthProbe.java

FROM ${JAVA_IMAGE} AS runtime
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/blackirlsama/Tianmu" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.licenses="MIT"
WORKDIR /opt/tianmu
COPY --from=build /build/target/operations-agent-0.7.0-SNAPSHOT.jar /opt/tianmu/app.jar
COPY --from=build /probe/classes /opt/tianmu/probe
COPY deploy/entrypoint.sh /opt/tianmu/entrypoint.sh
RUN mkdir -p /data/artifacts /data/trace \
    && chown -R 10001:10001 /data \
    && chmod 0555 /opt/tianmu/entrypoint.sh
USER 10001:10001
EXPOSE 8201
HEALTHCHECK --interval=15s --timeout=10s --start-period=90s --retries=4 \
  CMD ["java", "-Xms8m", "-Xmx32m", "-cp", "/opt/tianmu/probe", "HealthProbe", "http://127.0.0.1:8201/actuator/health/readiness"]
ENTRYPOINT ["/bin/sh", "/opt/tianmu/entrypoint.sh"]
