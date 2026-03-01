package org.gostforge.backend.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.common.ApiException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.*;

/**
 * Content-Addressable Storage backed by MinIO.
 * Files are keyed by SHA-256 hash under the "cas/" prefix.
 *
 * VS Code extension flow:
 * 1. Client computes SHA-256 for all project files and calls check-hashes
 * 2. Server returns list of hashes NOT present in CAS
 * 3. Client re-sends only the missing files + full manifest (path → hash)
 * 4. Server verifies and stores uploaded files in CAS, then assembles the workspace
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CasService {

    private final MinioStorageService minio;

    private static final String CAS_PREFIX = "cas/";

    /**
     * Given a manifest (path → sha256), return the paths whose content is NOT in CAS.
     */
    public List<String> checkHashes(Map<String, String> manifest) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String hash = entry.getValue();
            if (!minio.objectExists(casKey(hash))) {
                missing.add(entry.getKey());
            }
        }
        return missing;
    }

    /**
     * Store a file in CAS under its declared hash, after verifying the actual hash matches.
     * @param declaredHash The SHA-256 hex hash from the manifest.
     * @param data The file bytes.
     * @param path The file path (for error messages).
     */
    public void storeVerified(String declaredHash, byte[] data, String path) {
        String actualHash = sha256Hex(data);
        if (!actualHash.equalsIgnoreCase(declaredHash)) {
            throw ApiException.badRequest("HASH_MISMATCH",
                    "Hash mismatch for " + path + ": declared=" + declaredHash + ", actual=" + actualHash);
        }
        if (!minio.objectExists(casKey(declaredHash))) {
            minio.putObject(casKey(declaredHash), data, "application/octet-stream");
        }
    }

    /**
     * Retrieve bytes from CAS by hash. Returns null if not found.
     */
    public byte[] get(String hash) {
        String key = casKey(hash);
        if (!minio.objectExists(key)) {
            return null;
        }
        return minio.getObject(key);
    }

    /**
     * Check whether a hash exists in CAS.
     */
    public boolean exists(String hash) {
        return minio.objectExists(casKey(hash));
    }

    private String casKey(String hash) {
        return CAS_PREFIX + hash;
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
