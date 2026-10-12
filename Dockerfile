# CI builds this artifact only after tests of the exact same commit succeed.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 account && useradd --uid 10001 --gid 10001 --no-create-home account
COPY --chown=10001:10001 build/libs/*-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
