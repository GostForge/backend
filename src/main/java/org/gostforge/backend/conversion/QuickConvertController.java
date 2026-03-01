package org.gostforge.backend.conversion;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.conversion.dto.JobStatusResponse;
import org.gostforge.backend.storage.CasService;
import org.gostforge.backend.storage.MinioStorageService;
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
public class QuickConvertController {

    private final ConversionService conversionService;
    private final ConversionJobRepository jobRepository;
    private final MinioStorageService minioStorage;
    private final CasService casService;

    private static final ObjectMapper OM = new ObjectMapper();
    private final ScheduledExecutorService sseExecutor = Executors.newScheduledThreadPool(2);

    // ── Check Hashes (VS Code) ───────────────────

    @PostMapping("/api/v1/convert/quick/check-hashes")
    public ResponseEntity<Map<String, Object>> checkHashes(
            Authentication auth,
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, String> hashes = (Map<String, String>) body.get("hashes");
        if (hashes == null || hashes.isEmpty()) {
            throw ApiException.badRequest("MISSING_HASHES", "Field 'hashes' is required");
        }

        List<String> missing = casService.checkHashes(hashes);
        return ResponseEntity.ok(Map.of("missing", missing));
    }

    // ── Public Quick Convert (ZIP or Files mode) ──

    @PostMapping("/api/v1/convert/quick")
    public ResponseEntity<JobStatusResponse> quickConvert(
            Authentication auth,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "archive", required = false) MultipartFile archive,
            @RequestParam(value = "files[]", required = false) List<MultipartFile> files,
            @RequestParam(value = "manifest", required = false) String manifestJson,
            @RequestParam(value = "outputFormat", defaultValue = "DOCX") String outputFormat,
            @RequestParam(value = "options", required = false) String optionsJson) {

        UUID userId = (UUID) auth.getPrincipal();
        String resolvedFormat = resolveOutputFormat(outputFormat, optionsJson);

        // ── Mode 2: Files + Manifest (VS Code) ──
        if (manifestJson != null && !manifestJson.isBlank()) {
            Map<String, String> manifest = parseManifest(manifestJson);
            Map<String, byte[]> uploadedFiles = collectUploadedFiles(files);
            try {
                return ResponseEntity.accepted().body(
                        conversionService.submitJobFromManifest(userId, resolvedFormat, manifest, uploadedFiles));
            } catch (StaleCacheException e) {
                // 409 STALE_CACHE — client should re-upload the listed files
                return ResponseEntity.status(409).body(
                        JobStatusResponse.builder()
                                .status("STALE_CACHE")
                                .errorMessage(String.join(",", e.getMissingPaths()))
                                .build());
            }
        }

        // ── Mode 1: ZIP / single file (TG Bot, web) ──
        MultipartFile input = file != null ? file : archive;
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("MISSING_FILE", "No file, archive, or manifest provided");
        }
        return ResponseEntity.accepted().body(
                conversionService.submitJob(userId, resolvedFormat, input));
    }

    // ── Other public endpoints ──────────────────

    @PostMapping("/api/v1/convert/docx")
    public ResponseEntity<byte[]> convertToDocx(Authentication auth,
                                                @RequestParam("file") MultipartFile file) {
        UUID userId = (UUID) auth.getPrincipal();
        JobStatusResponse job = conversionService.submitJob(userId, "DOCX", file);
        return waitAndDownload(job.getJobId(), userId, "docx");
    }

    @PostMapping("/api/v1/convert/pdf")
    public ResponseEntity<byte[]> convertToPdf(Authentication auth,
                                               @RequestParam("file") MultipartFile file) {
        UUID userId = (UUID) auth.getPrincipal();
        JobStatusResponse job = conversionService.submitJob(userId, "PDF", file);
        return waitAndDownload(job.getJobId(), userId, "pdf");
    }

    @GetMapping("/api/v1/convert/quick/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(Authentication auth,
                                                          @PathVariable UUID jobId) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(conversionService.getJobStatus(jobId, userId));
    }

    @GetMapping("/api/v1/convert/quick/jobs/{jobId}/download/{format}")
    public ResponseEntity<InputStreamResource> downloadResult(
            Authentication auth,
            @PathVariable UUID jobId,
            @PathVariable String format) {

        UUID userId = (UUID) auth.getPrincipal();
        ConversionJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> ApiException.notFound("Job not found"));

        if (!"COMPLETED".equals(job.getStatus())) {
            throw ApiException.conflict("JOB_NOT_COMPLETE", "Job is not completed yet");
        }

        return buildDownloadResponse(job, format);
    }

    @GetMapping("/api/v1/convert/quick/jobs/{jobId}/stream")
    public SseEmitter streamStatus(Authentication auth, @PathVariable UUID jobId) {
        UUID userId = (UUID) auth.getPrincipal();
        SseEmitter emitter = new SseEmitter(300_000L);

        sseExecutor.scheduleAtFixedRate(() -> {
            try {
                ConversionJob job = jobRepository.findByIdAndUserId(jobId, userId).orElse(null);
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

    // ── Internal API (TG Bot) ────────────────────

    @PostMapping("/internal/convert/quick")
    public ResponseEntity<JobStatusResponse> internalQuickConvert(
            Authentication auth,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "archive", required = false) MultipartFile archive,
            @RequestParam(value = "outputFormat", defaultValue = "DOCX") String outputFormat,
            @RequestParam(value = "options", required = false) String optionsJson) {

        UUID userId = (UUID) auth.getPrincipal();
        MultipartFile input = file != null ? file : archive;
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("MISSING_FILE", "No file or archive provided");
        }
        String resolvedFormat = resolveOutputFormat(outputFormat, optionsJson);
        return ResponseEntity.accepted().body(
                conversionService.submitJob(userId, resolvedFormat, input));
    }

    @GetMapping("/internal/convert/quick/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> internalJobStatus(@PathVariable UUID jobId) {
        ConversionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("Job not found"));
        return ResponseEntity.ok(JobStatusResponse.builder()
                .jobId(job.getId())
                .status(job.getStatus())
                .outputFormat(job.getOutputFormat())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .build());
    }

    @GetMapping("/internal/convert/quick/jobs/{jobId}/download/{format}")
    public ResponseEntity<InputStreamResource> internalDownload(
            @PathVariable UUID jobId,
            @PathVariable String format) {

        ConversionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("Job not found"));

        if (!"COMPLETED".equals(job.getStatus())) {
            throw ApiException.conflict("JOB_NOT_COMPLETE", "Job not completed");
        }

        return buildDownloadResponse(job, format);
    }

    // ── Helpers ──────────────────────────────────

    private ResponseEntity<InputStreamResource> buildDownloadResponse(ConversionJob job, String format) {
        String key = "pdf".equalsIgnoreCase(format) ? job.getPdfKey() : job.getDocxKey();
        String contentType = "pdf".equalsIgnoreCase(format) ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        String filename = "pdf".equalsIgnoreCase(format) ? "result.pdf" : "output.docx";

        if (key == null) throw ApiException.notFound("Requested format not available");

        InputStream stream = minioStorage.getObjectStream(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new InputStreamResource(stream));
    }

    private ResponseEntity<byte[]> waitAndDownload(UUID jobId, UUID userId, String format) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ConversionJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) throw ApiException.notFound("Job not found");

            if ("COMPLETED".equals(job.getStatus())) {
                String key = "pdf".equals(format) ? job.getPdfKey() : job.getDocxKey();
                if (key == null) throw ApiException.notFound("Format not available");
                byte[] data = minioStorage.getObject(key);
                String contentType = "pdf".equals(format) ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                String filename = "pdf".equals(format) ? "result.pdf" : "output.docx";
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(data);
            }

            if ("FAILED".equals(job.getStatus())) {
                throw new RuntimeException("Conversion failed: " + job.getErrorMessage());
            }

            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for conversion");
            }
        }
        throw new RuntimeException("Conversion timed out");
    }

    private String resolveOutputFormat(String directFormat, String optionsJson) {
        if (optionsJson != null && !optionsJson.isBlank()) {
            try {
                JsonNode node = OM.readTree(optionsJson);
                JsonNode fmt = node.get("outputFormat");
                if (fmt != null && !fmt.isNull()) {
                    return fmt.asText();
                }
            } catch (Exception ignored) { }
        }
        return directFormat;
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
                throw new RuntimeException("Failed to read uploaded file: " + name, e);
            }
        }
        return result;
    }
}
