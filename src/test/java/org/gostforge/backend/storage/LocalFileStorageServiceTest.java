package org.gostforge.backend.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void usesPrimaryStorageWhenWritable() {
        Path primary = tempDir.resolve("primary");

        LocalFileStorageService service = new LocalFileStorageService(primary.toString(), true, "");
        service.init();

        service.putObject("job-1/output.md", "hello".getBytes(StandardCharsets.UTF_8), "text/markdown");

        assertTrue(Files.exists(primary.resolve("job-1/output.md")));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), service.getObject("job-1/output.md"));
    }

    @Test
    void fallsBackToEmergencyStorageWhenPrimaryUnavailable() throws Exception {
        Path primaryAsFile = tempDir.resolve("primary-file");
        Files.writeString(primaryAsFile, "not-a-directory", StandardCharsets.UTF_8);
        Path emergency = tempDir.resolve("emergency");

        LocalFileStorageService service = new LocalFileStorageService(primaryAsFile.toString(), true, emergency.toString());
        service.init();

        service.putObject("job-2/result.md", "ok".getBytes(StandardCharsets.UTF_8), "text/markdown");

        assertTrue(Files.exists(emergency.resolve("job-2/result.md")));
        assertFalse(Files.exists(primaryAsFile.resolve("job-2/result.md")));
    }

    @Test
    void rejectsPathTraversalKeys() {
        Path primary = tempDir.resolve("primary");
        LocalFileStorageService service = new LocalFileStorageService(primary.toString(), true, "");
        service.init();

        StorageException ex = assertThrows(
                StorageException.class,
                () -> service.putObject("../escape.txt", new byte[] {1}, "application/octet-stream")
        );
        assertTrue(ex.getMessage().contains("Failed to save file"));
    }

    @Test
    void failsWhenPrimaryUnavailableAndFallbackDisabled() throws Exception {
        Path primaryAsFile = tempDir.resolve("primary-file-no-fallback");
        Files.writeString(primaryAsFile, "not-a-directory", StandardCharsets.UTF_8);

        LocalFileStorageService service = new LocalFileStorageService(primaryAsFile.toString(), false, "");

        assertThrows(StorageException.class, service::init);
    }
}
