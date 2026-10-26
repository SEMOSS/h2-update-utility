package org.semoss.h2.migration;

import java.util.Map;
import java.util.Objects;

/** Provider-neutral metadata used to detect concurrent remote object changes. */
public record CloudObjectMetadata(
        long size,
        String modTime,
        Map<String, String> hashes,
        String version
) {
    public CloudObjectMetadata {
        modTime = modTime == null ? "" : modTime;
        hashes = Map.copyOf(hashes == null ? Map.of() : hashes);
        version = version == null ? "" : version;
    }

    public CloudObjectMetadata(long size, String modTime, Map<String, String> hashes) {
        this(size, modTime, hashes, "");
    }

    public boolean sameSourceVersion(CloudObjectMetadata other) {
        if (other == null || size != other.size) return false;
        if (!version.isBlank() && !other.version.isBlank()
                && !version.equals(other.version)) {
            return false;
        }
        if (!hashes.isEmpty() && !other.hashes.isEmpty()) {
            for (Map.Entry<String, String> hash : hashes.entrySet()) {
                String otherHash = other.hashes.get(hash.getKey());
                if (otherHash != null && !hash.getValue().equalsIgnoreCase(otherHash)) {
                    return false;
                }
            }
        }
        return Objects.equals(modTime, other.modTime);
    }
}
