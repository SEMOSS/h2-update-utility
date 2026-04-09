# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests -q && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Runtime stage
FROM eclipse-temurin:21-jre
LABEL maintainer="semoss"
LABEL description="H2 Database Migration Service (1.4.x → 2.x)"

# Install rclone
RUN apt-get update && \
    apt-get install -y curl unzip && \
    curl -fsSL https://rclone.org/install.sh | bash && \
    apt-get remove -y curl unzip && \
    apt-get autoremove -y && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r migrator && useradd -r -g migrator -d /app migrator

WORKDIR /app
COPY --from=build /build/target/h2-migration-service.jar /app/h2-migration-service.jar

# Default directories
RUN mkdir -p /data /backup && chown -R migrator:migrator /app /data /backup

USER migrator

# Default entrypoint — override CMD or pass args
ENTRYPOINT ["java", "-jar", "/app/h2-migration-service.jar"]
CMD ["--help"]
