package org.gostforge.backend.conversion;

import lombok.Getter;
import java.util.List;

/**
 * Thrown when the client's hash-cache is stale: some manifest entries
 * point to CAS objects that do not exist.  The controller catches this
 * and returns 409 STALE_CACHE with the list of missing paths so the
 * client can re-upload them.
 */
@Getter
public class StaleCacheException extends RuntimeException {

    private final List<String> missingPaths;

    public StaleCacheException(List<String> missingPaths) {
        super("Stale cache: " + missingPaths.size() + " files missing from CAS");
        this.missingPaths = missingPaths;
    }
}
