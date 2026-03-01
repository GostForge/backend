package org.gostforge.backend.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.conversion.dto.JobStatusResponse;
import org.gostforge.backend.storage.CasService;
import org.gostforge.backend.storage.MinioStorageService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class ConversionService {

    private final ConversionJobRepository jobRepository;
    private final MinioStorageService minioStorage;
    private final CasService casService;
    private final StringRedisTemplate redis;
    private final Map<ConversionFormat, Map<ConversionFormat, FormatConverter>> converterMap;

    private static final String QUEUE_KEY = "gostforge:conversion:queue";
    private static final List<String> ACTIVE_STATUSES =
            List.of("PENDING", "MERGING_MD", "CONVERTING_DOCX", "CONVERTING_PDF");
    private static final List<String> PROCESSING_STATUSES =
            List.of("MERGING_MD", "CONVERTING_DOCX", "CONVERTING_PDF");
    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * Spring injects all {@link FormatConverter} beans; we index them
     * by (inputFormat → outputFormat) for O(1) lookup.
     */
    public ConversionService(
            ConversionJobRepository jobRepository,
            MinioStorageService minioStorage,
            CasService casService,
            StringRedisTemplate redis,
            List<FormatConverter> converters) {
        this.jobRepository = jobRepository;
        this.minioStorage = minioStorage;
        this.casService = casService;
        this.redis = redis;

        this.converterMap = new EnumMap<>(ConversionFormat.class);
        for (FormatConverter c : converters) {
            converterMap
                    .computeIfAbsent(c.inputFormat(), k -> new EnumMap<>(ConversionFormat.class))
                    .put(c.outputFormat(), c);
        }
        log.info("Registered {} format converter(s): {}", converters.size(),
                converters.stream()
                        .map(c -> c.inputFormat() + "→" + c.outputFormat())
                        .toList());
    }

    /**
     * Look up the converter for a given transition, e.g. MARKDOWN→DOCX.
     */
    private FormatConverter getConverter(ConversionFormat from, ConversionFormat to) {
        FormatConverter c = converterMap.getOrDefault(from, Map.of()).get(to);
        if (c == null) {
            throw new RuntimeException("No converter registered for " + from + " → " + to);
        }
        return c;
    }

    /**
     * Crash recovery: flush Redis queue and mark all in-flight jobs as FAILED.
     * Runs after full context is ready (ApplicationReadyEvent) so @Transactional works.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void crashRecovery() {
        try {
            redis.delete(QUEUE_KEY);
            int recovered = jobRepository.markCrashRecovery(ACTIVE_STATUSES);
            if (recovered > 0) {
                log.warn("Crash recovery: marked {} jobs as FAILED", recovered);
            }
        } catch (Exception e) {
            log.error("Crash recovery failed: {}", e.getMessage());
        }
    }

    @Transactional
    public JobStatusResponse submitJob(UUID userId, String outputFormat, MultipartFile file) {
        // Check for active job
        List<ConversionJob> active = jobRepository.findActiveJobs(userId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw ApiException.conflict("ACTIVE_JOB", "You already have an active conversion job");
        }

        ConversionJob job = ConversionJob.builder()
                .userId(userId)
                .outputFormat(outputFormat != null ? outputFormat.toUpperCase() : "DOCX")
                .build();
        job = jobRepository.save(job);

        // Store the uploaded file in MinIO for the worker
        // If it's a ZIP, extract the first .md file; otherwise store directly
        String mdKey = "quick/" + job.getId() + "/input.md";
        try {
            byte[] rawBytes = file.getBytes();
            byte[] mdBytes = extractMdFromZipOrRaw(rawBytes, file.getOriginalFilename());
            minioStorage.putObject(mdKey, mdBytes, "text/markdown");
        } catch (Exception e) {
            throw new RuntimeException("Failed to store uploaded file", e);
        }
        job.setMergedMdKey(mdKey);
        jobRepository.save(job);

        // Enqueue AFTER transaction commits so the worker can find the DB row
        UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.opsForList().leftPush(QUEUE_KEY, jobId.toString());
            }
        });

        return toStatusResponse(job);
    }

    /**
     * Submit a job from VS Code manifest-based upload.
     * files: only the missing files the client re-uploaded.
     * manifest: full path→sha256 map of ALL project files.
     */
    @Transactional
    public JobStatusResponse submitJobFromManifest(UUID userId, String outputFormat,
                                                   Map<String, String> manifest,
                                                   Map<String, byte[]> uploadedFiles) {
        // Check for active job
        List<ConversionJob> active = jobRepository.findActiveJobs(userId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw ApiException.conflict("ACTIVE_JOB", "You already have an active conversion job");
        }

        // Verify uploaded files are all in manifest
        for (String path : uploadedFiles.keySet()) {
            if (!manifest.containsKey(path)) {
                throw ApiException.badRequest("UNKNOWN_FILE",
                        "Uploaded file '" + path + "' is not in manifest");
            }
        }

        // Store uploaded files in CAS (with hash verification)
        for (Map.Entry<String, byte[]> entry : uploadedFiles.entrySet()) {
            String path = entry.getKey();
            String declaredHash = manifest.get(path);
            casService.storeVerified(declaredHash, entry.getValue(), path);
        }

        // Verify all manifest entries exist in CAS now
        List<String> stillMissing = new ArrayList<>();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            if (!casService.exists(entry.getValue())) {
                stillMissing.add(entry.getKey());
            }
        }
        if (!stillMissing.isEmpty()) {
            throw new StaleCacheException(stillMissing);
        }

        // Create job
        ConversionJob job = ConversionJob.builder()
                .userId(userId)
                .outputFormat(outputFormat != null ? outputFormat.toUpperCase() : "DOCX")
                .build();
        job = jobRepository.save(job);

        // Assemble all files from CAS into job workspace in MinIO
        String jobPrefix = "quick/" + job.getId() + "/";
        String mdKey = null;
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            byte[] data = casService.get(hash);
            if (data == null) {
                throw new RuntimeException("CAS miss after verification: " + path);
            }
            String objectKey = jobPrefix + path;
            minioStorage.putObject(objectKey, data, guessContentType(path));

            // Find the main .md file
            if (path.endsWith(".md") && (mdKey == null || path.length() < mdKey.length())) {
                mdKey = objectKey;
            }
        }

        if (mdKey == null) {
            throw ApiException.badRequest("NO_MD_FILE", "Manifest contains no .md file");
        }

        job.setMergedMdKey(mdKey);
        jobRepository.save(job);

        UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.opsForList().leftPush(QUEUE_KEY, jobId.toString());
            }
        });

        return toStatusResponse(job);
    }

    @Transactional(readOnly = true)
    public JobStatusResponse getJobStatus(UUID jobId, UUID userId) {
        ConversionJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> ApiException.notFound("Job not found"));
        return toStatusResponse(job);
    }

    /**
     * Called by ConversionWorker after BRPOP.
     * Uses the {@link FormatConverter} pipeline for actual conversions.
     */
    @Transactional
    public void processJob(UUID jobId) {
        ConversionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !"PENDING".equals(job.getStatus())) {
            log.warn("Skipping job {} (status={}, exists={})",
                    jobId, job != null ? job.getStatus() : "N/A", job != null);
            return;
        }

        job.setStatus("MERGING_MD");
        job.setStartedAt(Instant.now());
        jobRepository.save(job);

        try {
            // Step 1: Download the input MD from MinIO
            byte[] mdBytes = minioStorage.getObject(job.getMergedMdKey());

            // Step 1b: Collect asset files (images, etc.) for manifest-based jobs
            String jobPrefix = "quick/" + jobId + "/";
            Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
            List<String> allKeys = minioStorage.listObjects(jobPrefix);
            String mdKey = job.getMergedMdKey();
            for (String key : allKeys) {
                if (key.equals(mdKey) || key.contains("/output.") || key.contains("/result.")) {
                    continue;
                }
                String relativePath = key.substring(jobPrefix.length());
                if (!relativePath.isEmpty()) {
                    assets.put(relativePath, minioStorage.getObject(key));
                }
            }

            OutputFormat fmt = OutputFormat.fromString(job.getOutputFormat());
            List<String> allWarnings = new ArrayList<>();

            // Step 2: MARKDOWN → DOCX via converter pipeline
            job.setStatus("CONVERTING_DOCX");
            jobRepository.save(job);

            FormatConverter md2docx = getConverter(ConversionFormat.MARKDOWN, ConversionFormat.DOCX);
            FormatConverter.ConversionResult docxResult = md2docx.convert(mdBytes, assets);
            byte[] docxBytes = docxResult.data();
            allWarnings.addAll(docxResult.warnings());

            String docxKey = "quick/" + jobId + "/output.docx";
            minioStorage.putObject(docxKey, docxBytes,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            job.setDocxKey(docxKey);

            // Step 3: DOCX → PDF via converter pipeline (if needed)
            if (fmt == OutputFormat.PDF || fmt == OutputFormat.BOTH) {
                job.setStatus("CONVERTING_PDF");
                jobRepository.save(job);

                FormatConverter docx2pdf = getConverter(ConversionFormat.DOCX, ConversionFormat.PDF);
                FormatConverter.ConversionResult pdfResult = docx2pdf.convert(docxBytes, Map.of());
                allWarnings.addAll(pdfResult.warnings());

                String pdfKey = "quick/" + jobId + "/result.pdf";
                minioStorage.putObject(pdfKey, pdfResult.data(), "application/pdf");
                job.setPdfKey(pdfKey);
            }

            // Store warnings as JSON array
            if (!allWarnings.isEmpty()) {
                try {
                    job.setWarnings(OM.writeValueAsString(allWarnings));
                } catch (Exception e) {
                    log.warn("Failed to serialize warnings: {}", e.getMessage());
                }
            }

            job.setStatus("COMPLETED");
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            log.info("Job {} completed successfully (warnings: {})", jobId, allWarnings.size());

        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            String currentStage = job.getStatus();
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage());
            if (job.getErrorStage() == null) {
                job.setErrorStage(
                    "CONVERTING_PDF".equals(currentStage) ? "CONVERTING_PDF" : "CONVERTING_DOCX");
            }
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
        }
    }

    /**
     * Timeout checker: marks jobs stuck for > 10 minutes as FAILED
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void checkTimeouts() {
        Instant cutoff = Instant.now().minusSeconds(600);
        int timedOut = jobRepository.markTimedOut(PROCESSING_STATUSES, cutoff);
        if (timedOut > 0) {
            log.warn("Marked {} timed-out jobs as FAILED", timedOut);
        }
    }

    private JobStatusResponse toStatusResponse(ConversionJob job) {
        Integer queuePos = null;
        if ("PENDING".equals(job.getStatus())) {
            Long pos = redis.opsForList().indexOf(QUEUE_KEY, job.getId().toString());
            if (pos != null && pos >= 0) {
                queuePos = pos.intValue() + 1;
            }
        }

        List<String> warnings = parseWarningsJson(job.getWarnings());

        return JobStatusResponse.builder()
                .jobId(job.getId())
                .status(job.getStatus())
                .queuePosition(queuePos)
                .outputFormat(job.getOutputFormat())
                .errorStage(job.getErrorStage())
                .errorMessage(job.getErrorMessage())
                .warnings(warnings.isEmpty() ? null : warnings)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    private List<String> parseWarningsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OM.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * If the raw bytes are a ZIP archive, extract the first .md file from it.
     * Otherwise, return the raw bytes as-is (assumed to be a plain .md file).
     */
    private byte[] extractMdFromZipOrRaw(byte[] rawBytes, String originalFilename) throws IOException {
        boolean isZip = (originalFilename != null && originalFilename.endsWith(".zip"))
                || (rawBytes.length >= 4
                    && rawBytes[0] == 0x50 && rawBytes[1] == 0x4B
                    && rawBytes[2] == 0x03 && rawBytes[3] == 0x04);

        if (!isZip) {
            return rawBytes;
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(rawBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".md")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        baos.write(buf, 0, n);
                    }
                    log.info("Extracted '{}' ({} bytes) from ZIP", entry.getName(), baos.size());
                    return baos.toByteArray();
                }
            }
        }
        throw new RuntimeException("ZIP archive does not contain any .md file");
    }

    private String guessContentType(String path) {
        if (path.endsWith(".md")) return "text/markdown";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".bmp")) return "image/bmp";
        if (path.endsWith(".yml") || path.endsWith(".yaml")) return "text/yaml";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
