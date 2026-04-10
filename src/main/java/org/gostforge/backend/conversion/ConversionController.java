package org.gostforge.backend.conversion;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.conversion.dto.JobStatusResponse;
import org.gostforge.backend.storage.MinioStorageService;
import org.gostforge.backend.storage.CasService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversions")
public class ConversionController {

    private final ConversionService conversionService;
    private final ConversionJobRepository jobRepository;
    private final MinioStorageService minioStorage;
    private final CasService casService;

    private static final ObjectMapper OM = new ObjectMapper();
    private final ScheduledExecutorService sseExecutor = Executors.newScheduledThreadPool(2);

    @PostMapping("/check-hashes")
    public ResponseEntity<Map<String, Object>> checkHashes(
            Authentication auth,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, String> hashes = (Map<String, String>) body.get("hashes");
        if (hashes == null || hashes.isEmpty()) {
            throw ApiException.badRequest("MISSING_HASHES", "Field 'hashes' is required");
        }
        return ResponseEntity.ok(Map.of("missing", casService.checkHashes(hashes)));
    }

    @PostMapping
    public ResponseEntity<JobStatusResponse> submitConversionJob(
            Authentication auth,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "archive", required = false) MultipartFile archive,
            @RequestParam(value = "files[]", required = false) List<MultipartFile> files,
            @RequestParam(value = "manifest", required = false) String manifestJson,
            @RequestParam(value = "outputFormat", defaultValue = "DOCX") String outputFormat,
            @RequestParam(value = "options", required = false) String optionsJson) {

        UUID userId = (UUID) auth.getPrincipal();
        String resolvedFormat = resolveOutputFormat(outputFormat, optionsJson);

        if (manifestJson != null && !manifestJson.isBlank()) {
            Map<String, String> manifest = parseManifest(manifestJson);
            Map<String, byte[]> uploadedFiles = collectUploadedFiles(files);
            try {
                return ResponseEntity.accepted().body(
                        conversionService.submitJobFromManifest(userId, resolvedFormat, manifest, uploadedFiles));
            } catch (StaleCacheException e) {
                return ResponseEntity.status(409).body(
                        JobStatusResponse.builder()
                                .status("STALE_CACHE")
                                .errorMessage(String.join(",", e.getMissingPaths()))
                                .build());
            }
        }

        MultipartFile input = file != null ? file : archive;
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("MISSING_FILE", "No file, archive, or manifest provided");
        }
        return ResponseEntity.accepted().body(
                conversionService.submitJob(userId, resolvedFormat, input));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(Authentication auth, @PathVariable UUID jobId) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(conversionService.getJobStatus(jobId, userId));
    }

    @GetMapping("/{jobId}/result")
    public ResponseEntity<InputStreamResource> downloadResult(Authentication auth, @PathVariable UUID jobId) {
        UUID userId = (UUID) auth.getPrincipal();
        ConversionJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> ApiException.notFound("Job not found"));

        if (!"COMPLETED".equals(job.getStatus())) {
            throw ApiException.conflict("JOB_NOT_COMPLETE", "Job is not completed yet");
        }

        String format = job.getOutputFormat().toLowerCase();
        String key = "pdf".equalsIgnoreCase(format) ? job.getPdfKey() : ("markdown".equalsIgnoreCase(format) ? job.getMergedMdKey() : job.getDocxKey());
        String contentType = "pdf".equalsIgnoreCase(format) ? "application/pdf" : ("markdown".equalsIgnoreCase(format) ? "application/zip" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        String filename = "markdown".equalsIgnoreCase(format) ? "result.zip" : ("output." + format);

        if (key == null) {
            throw ApiException.notFound("Result file is missing");
        }

        InputStream stream = minioStorage.getObjectStream(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new InputStreamResource(stream));
    }

    @GetMapping("/{jobId}/stream")
    public SseEmitter streamStatus(Authentication auth, @PathVariable UUID jobId) {
        UUID userId = auth != null ? (UUID) auth.getPrincipal() : null;
        SseEmitter emitter = new SseEmitter(300_000L);

        sseExecutor.scheduleAtFixedRate(() -> {
            try {
                ConversionJob job;
                if (userId != null) {
                    job = jobRepository.findByIdAndUserId(jobId, userId).orElse(null);
                } else {
                    job = jobRepository.findById(jobId).orElse(null);
                }
                
                if (job == null) {
                    emitter.completeWithError(new RuntimeException("Job not found"));
                    return;
                }

                String event = switch (job.getStatus()) {
                    case "COMPLETED" -> "completed";
                    case "FAILED" -> "failed";
                    case "PENDING" -> "queued";
                    default -> "status";
                };

                emitter.send(SseEmitter.event().name(event).data(Map.of(
                        "jobId", job.getId().toString(),
                        "status", job.getStatus()
                )));

                if ("COMPLETED".equals(job.getStatus()) || "FAILED".equals(job.getStatus())) {
                    emitter.complete();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, 0, 2, TimeUnit.SECONDS);

        return emitter;
    }

    private String resolveOutputFormat(String directFormat, String optionsJson) {
        if (optionsJson != null && !optionsJson.isBlank()) {
            try {
                JsonNode node = OM.readTree(optionsJson);
                JsonNode fmt = node.get("outputFormat");
                if (fmt != null && !fmt.isNull()) {
                    return fmt.asText().toUpperCase();
                }
            } catch (Exception ignored) { }
        }
        return directFormat.toUpperCase();
    }

    private Map<String, String> parseManifest(String json) {
        try {
            return OM.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_MANIFEST", "Failed to parse manifest JSON");
        }
    }

    private Map<String, byte[]> collectUploadedFiles(List<MultipartFile> files) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) return result;
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            if (name == null) name = f.getName();
            try {
                result.put(name, f.getBytes());
            } catch (Exception e) {
                throw new RuntimeException("Failed to read part: " + name, e);
            }
        }
        return result;
    }
}
