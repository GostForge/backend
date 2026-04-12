package org.gostforge.backend.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocalFileStorageService {

    private final Path rootLocation = Paths.get("uploads");

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage folder", e);
        }
    }

    public void putObject(String key, byte[] data, String contentType) {
        try {
            Path target = rootLocation.resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save file: " + key, e);
        }
    }

    public byte[] getObject(String key) {
        try {
            return Files.readAllBytes(rootLocation.resolve(key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + key, e);
        }
    }

    public InputStream getObjectStream(String key) {
        try {
            return Files.newInputStream(rootLocation.resolve(key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to stream file: " + key, e);
        }
    }

    public void deleteObject(String key) {
        try {
            Files.deleteIfExists(rootLocation.resolve(key));
        } catch (Exception e) {
            log.warn("Failed to delete file: {}", key, e);
        }
    }

    public boolean objectExists(String key) {
        return Files.exists(rootLocation.resolve(key));
    }

    public List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            Path prefixPath = rootLocation.resolve(prefix);
            if (!Files.exists(prefixPath)) return keys;

            Files.walk(prefixPath)
                    .filter(Files::isRegularFile)
                    .forEach(p -> keys.add(rootLocation.relativize(p).toString().replace("\\", "/")));
        } catch (Exception e) {
            throw new RuntimeException("Failed to list objects: " + prefix, e);
        }
        return keys;
    }
}