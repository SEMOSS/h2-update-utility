package org.semoss.h2.migration;

import java.util.Locale;

/** Selects the provider transport without leaking it into migration orchestration. */
final class CloudObjectStoreFactory {

    private CloudObjectStoreFactory() {}

    static CloudObjectStore fromConfig(MigrationConfig config) {
        CloudStorageLayout.Provider provider = CloudStorageLayout.fromConfig(config).provider();
        if (provider != CloudStorageLayout.Provider.GCS) {
            return RCloneClient.fromConfig(config);
        }

        String backend = config.gcsTransferBackend() == null
                ? MigrationConfig.DEFAULT_GCS_TRANSFER_BACKEND
                : config.gcsTransferBackend().trim().toLowerCase(Locale.ROOT);
        return switch (backend) {
            case "rclone" -> RCloneClient.fromConfig(config);
            case "gcloud" -> GcloudStorageClient.fromConfig(config);
            default -> throw new IllegalArgumentException(
                    "GCS_TRANSFER_BACKEND must be rclone or gcloud");
        };
    }
}
