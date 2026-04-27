package org.gostforge.backend.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class LocalFileStorageService {

    private final Path configuredRootLocation;
    private final boolean emergencyFallbackEnabled;
    private final Path emergencyRootLocation;
    private Path activeRootLocation;

    public LocalFileStorageService(
            @Value("${storage.local.root:uploads}") String storageRoot,
            @Value("${storage.local.enable-emergency-fallback:true}") boolean emergencyFallbackEnabled,
            @Value("${storage.local.emergency-root:}") String emergencyRootOverride) {
        this.configuredRootLocation = Paths.get(storageRoot).normalize();
        this.emergencyFallbackEnabled = emergencyFallbackEnabled;
        this.emergencyRootLocation = emergencyRootOverride == null || emergencyRootOverride.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "gostforge-uploads")
                : Paths.get(emergencyRootOverride).normalize();
    }

    @PostConstruct
    public void init() {
        if (initializeWritableDirectory(configuredRootLocation, "primary")) {
            activeRootLocation = configuredRootLocation;
            log.info("Using local storage directory: {}", activeRootLocation.toAbsolutePath());
            return;
        }

        if (!emergencyFallbackEnabled) {
            throw new StorageException("Could not initialize primary storage folder: "
                    + configuredRootLocation.toAbsolutePath());
        }

        log.warn("Primary storage dir is not writable, switching to emergency dir: {}",
                emergencyRootLocation.toAbsolutePath());

        if (!initializeWritableDirectory(emergencyRootLocation, "emergency")) {
            throw new StorageException("Could not initialize any writable storage directory");
        }

        activeRootLocation = emergencyRootLocation;
    }

    public void putObject(String key, byte[] data, String contentType) {
        try {
            Path target = resolveKeyPath(key);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            if (contentType != null && !contentType.isBlank() && log.isDebugEnabled()) {
                log.debug("Stored object {} (contentType={})", key, contentType);
            }
        } catch (Exception e) {
            throw new StorageException("Failed to save file: " + key, e);
        }
    }

    public byte[] getObject(String key) {
        try {
            return Files.readAllBytes(resolveKeyPath(key));
        } catch (Exception e) {
            throw new StorageException("Failed to read file: " + key, e);
        }
    }

    public InputStream getObjectStream(String key) {
        try {
            return Files.newInputStream(resolveKeyPath(key));
        } catch (Exception e) {
            throw new StorageException("Failed to stream file: " + key, e);
        }
    }

    public void deleteObject(String key) {
        try {
            Files.deleteIfExists(resolveKeyPath(key));
        } catch (Exception e) {
            log.warn("Failed to delete file: {}", key, e);
        }
    }

    public boolean objectExists(String key) {
        try {
            return Files.exists(resolveKeyPath(key));
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            Path prefixPath = prefix == null || prefix.isBlank()
                    ? activeRootLocation
                    : resolveKeyPath(prefix);
            if (!Files.exists(prefixPath)) return keys;

            try (Stream<Path> stream = Files.walk(prefixPath)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> keys.add(activeRootLocation.relativize(p).toString().replace("\\", "/")));
            }
        } catch (Exception e) {
            throw new StorageException("Failed to list objects: " + prefix, e);
        }
        return keys;
    }

    private boolean initializeWritableDirectory(Path directory, String label) {
        try {
            Files.createDirectories(directory);
            Path probe = directory.resolve(".write-probe-" + UUID.randomUUID());
            Files.write(probe, new byte[] {0x1});
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            log.error("Failed to initialize {} storage dir {}", label, directory.toAbsolutePath(), e);
            return false;
        }
    }

    private Path resolveKeyPath(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }

        Path resolved = activeRootLocation.resolve(key).normalize();
        if (!resolved.startsWith(activeRootLocation)) {
            throw new IllegalArgumentException("Invalid storage key path: " + key);
        }
        return resolved;
    }
}