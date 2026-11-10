# Host artifacts only: build backend and web first as documented in deploy/README.md.
# The JDK is intentional: the health probe uses Java source-file mode, not curl/wget.
FROM eclipse-temurin:17.0.17_10-jdk-jammy@sha256:73c73439d25b953b34e0065172ad8ce01bea5bc8fc25158f0eeecb4c06d14fed
WORKDIR /opt/approval
RUN groupadd --gid 10001 approval \
    && useradd --uid 10001 --gid 10001 --no-create-home approval \
    && mkdir -p /var/lib/approval/uploads \
    && chown 10001:10001 /var/lib/approval/uploads
COPY backend/target/approval-backend-0.1.0-SNAPSHOT.jar app.jar
COPY deploy/HealthProbe.java /opt/approval/HealthProbe.java
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/approval/app.jar"]
