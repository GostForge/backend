package org.gostforge.backend.storage;

import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.config.MinioProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioProperties props;

    public void putObject(String key, byte[] data, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload to MinIO: " + key, e);
        }
    }

    public byte[] getObject(String key) {
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(key)
                .build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download from MinIO: " + key, e);
        }
    }

    public InputStream getObjectStream(String key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to stream from MinIO: " + key, e);
        }
    }

    public void deleteObject(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete from MinIO: {}", key, e);
        }
    }

    public boolean objectExists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List all object keys under a given prefix.
     */
    public List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(props.getBucket())
                            .prefix(prefix)
                            .recursive(true)
                            .build());
            for (Result<Item> result : results) {
                keys.add(result.get().objectName());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to list objects with prefix: " + prefix, e);
        }
        return keys;
    }
}
