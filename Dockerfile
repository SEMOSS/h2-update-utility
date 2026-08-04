# Build stage
FROM azul/zulu-openjdk:21-latest AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests -q && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Runtime stage
FROM azul/zulu-openjdk:21-latest

LABEL org.opencontainers.image.title="H2 Migration Service"
LABEL org.opencontainers.image.description="Standalone migration tool for upgrading H2 databases from 1.4.x to 2.x"
LABEL org.opencontainers.image.source="https://github.com/SEMOSS/h2-migration-service"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.vendor="Semoss"

# Install rclone from a pinned release (override with --build-arg RCLONE_VERSION=...)
ARG RCLONE_VERSION=v1.71.0
ARG GCLOUD_VERSION=577.0.0
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl unzip ca-certificates python3 && \
    arch="$(dpkg --print-architecture)" && \
    curl -fsSL "https://downloads.rclone.org/${RCLONE_VERSION}/rclone-${RCLONE_VERSION}-linux-${arch}.zip" -o /tmp/rclone.zip && \
    unzip -j /tmp/rclone.zip "*/rclone" -d /usr/local/bin && \
    chmod 755 /usr/local/bin/rclone && \
    case "$arch" in amd64) gcloud_arch="x86_64" ;; arm64) gcloud_arch="arm" ;; *) exit 1 ;; esac && \
    curl -fsSL "https://storage.googleapis.com/cloud-sdk-release/google-cloud-cli-${GCLOUD_VERSION}-linux-${gcloud_arch}.tar.gz" -o /tmp/gcloud.tar.gz && \
    mkdir -p /opt && \
    tar -xzf /tmp/gcloud.tar.gz -C /opt && \
    CLOUDSDK_PYTHON=/usr/bin/python3 /opt/google-cloud-sdk/install.sh --quiet --path-update=false --command-completion=false --usage-reporting=false && \
    ln -s /opt/google-cloud-sdk/bin/gcloud /usr/local/bin/gcloud && \
    rm -f /tmp/rclone.zip /tmp/gcloud.tar.gz && \
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
