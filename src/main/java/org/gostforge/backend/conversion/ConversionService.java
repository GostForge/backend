package org.gostforge.backend.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.conversion.dto.JobStatusResponse;
import org.gostforge.backend.storage.CasService;
import org.gostforge.backend.storage.LocalFileStorageService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.gostforge.backend.conversion.MemoryQueue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class ConversionService {

    private final ConversionJobRepository jobRepository;
    private final LocalFileStorageService fileStorage;
    private final CasService casService;
    private final MemoryQueue queue;
    private final Map<ConversionFormat, Map<ConversionFormat, FormatConverter>> converterMap;

    private static final String QUEUE_KEY = "gostforge:conversion:queue";
    private static final List<String> ACTIVE_STATUSES =
            List.of("PENDING", "MERGING_MD", "CONVERTING_DOCX", "CONVERTING_PDF");
    private static final List<String> PROCESSING_STATUSES =
            List.of("MERGING_MD", "CONVERTING_DOCX", "CONVERTING_PDF");
    private static final ObjectMapper OM = new ObjectMapper();

    // ── ZIP safety limits ────────────────────────────────────────────────────
    /** Maximum number of entries allowed in a ZIP archive. */
    private static final int ZIP_MAX_ENTRIES = 500;
    /** Maximum total uncompressed size of all entries (200 MB). */
    private static final long ZIP_MAX_TOTAL_SIZE = 200L * 1024 * 1024;
    /** Maximum single-entry uncompressed size (50 MB). */
    private static final long ZIP_MAX_ENTRY_SIZE = 50L * 1024 * 1024;
    /** Compression ratio threshold — entries exceeding this are treated as a zip bomb. */
    private static final double ZIP_MAX_RATIO = 100.0;

    /**
     * Spring injects all {@link FormatConverter} beans; we index them
     * by (inputFormat → outputFormat) for O(1) lookup.
     */
    public ConversionService(
            ConversionJobRepository jobRepository,
            LocalFileStorageService fileStorage,
            CasService casService,
            MemoryQueue queue,
            List<FormatConverter> converters) {
        this.jobRepository = jobRepository;
        this.fileStorage = fileStorage;
        this.casService = casService;
        this.queue = queue;

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
            queue.clear();
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

        String jobPrefix = "quick/" + job.getId() + "/";
        boolean hasMd = false; boolean hasDocx = false;

        try {
            byte[] rawBytes = file.getBytes();
            String originalName = file.getOriginalFilename();

            if (isZip(rawBytes, originalName)) {
                // Extract full ZIP structure into MinIO with safety checks
                Map<String, byte[]> extracted = extractZip(rawBytes);
                for (Map.Entry<String, byte[]> entry : extracted.entrySet()) {
                    String path = entry.getKey();
                    fileStorage.putObject(jobPrefix + path, entry.getValue(), guessContentType(path));
                    if (path.endsWith(".md")) hasMd = true; else if (path.endsWith(".docx")) hasDocx = true;
                }
            } else {
                // Single .md file
                String name = (originalName != null && originalName.endsWith(".docx")) ? (originalName) : ((originalName != null && originalName.endsWith(".md")) ? originalName : "input.md");
                String contentType = name.endsWith(".docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "text/markdown";
                fileStorage.putObject(jobPrefix + name, rawBytes, contentType);
                if (name.endsWith(".md")) hasMd = true;
                if (name.endsWith(".docx")) hasDocx = true;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store uploaded file", e);
        }

        if ("MARKDOWN".equalsIgnoreCase(job.getOutputFormat())) {
            if (!hasDocx && !hasMd) throw ApiException.badRequest("NO_INPUT_FILE", "Upload contains no .docx or .md file");
        } else {
            if (!hasMd) throw ApiException.badRequest("NO_MD_FILE", "Upload contains no .md file");
        }

        jobRepository.save(job);

        // Enqueue AFTER transaction commits so the worker can find the DB row
        UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queue.push(jobId);
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
        boolean hasMd = false; boolean hasDocx = false;
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            byte[] data = casService.get(hash);
            if (data == null) {
                throw new RuntimeException("CAS miss after verification: " + path);
            }
            String objectKey = jobPrefix + path;
            fileStorage.putObject(objectKey, data, guessContentType(path));
            if (path.endsWith(".md")) hasMd = true; else if (path.endsWith(".docx")) hasDocx = true;
        }

        if ("MARKDOWN".equalsIgnoreCase(job.getOutputFormat())) { if (!hasDocx && !hasMd) throw ApiException.badRequest("NO_INPUT_FILE", "Upload contains no .docx or .md file"); } else if (!hasMd) {
            throw ApiException.badRequest("NO_MD_FILE", "Manifest contains no .md file");
        }

        jobRepository.save(job);

        UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queue.push(jobId);
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
            // Step 1: Collect ALL project files from MinIO job workspace
            String jobPrefix = "quick/" + jobId + "/";
            Map<String, byte[]> projectFiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            List<String> allKeys = fileStorage.listObjects(jobPrefix);
            for (String key : allKeys) {
                if (key.contains("/output.") || key.contains("/result.")) {
                    continue;
                }
                String relativePath = key.substring(jobPrefix.length());
                if (!relativePath.isEmpty()) {
                    projectFiles.put(relativePath, fileStorage.getObject(key));
                }
            }



            OutputFormat fmt = OutputFormat.fromString(job.getOutputFormat());
            List<String> allWarnings = new ArrayList<>();

            if (fmt == OutputFormat.MARKDOWN) {
                job.setStatus("CONVERTING_MD");
                jobRepository.save(job);
                FormatConverter docx2md = getConverter(ConversionFormat.DOCX, ConversionFormat.MARKDOWN);
                FormatConverter.ConversionResult mdResult = docx2md.convert(projectFiles);
                byte[] zipBytes = mdResult.data();
                allWarnings.addAll(mdResult.warnings());

                String zipKey = "quick/" + jobId + "/output-md.zip";
                fileStorage.putObject(zipKey, zipBytes, "application/zip");
                job.setMergedMdKey(zipKey);
            } else {
                // Step 2: MARKDOWN → DOCX via converter pipeline
                job.setStatus("CONVERTING_DOCX");
                jobRepository.save(job);

                FormatConverter md2docx = getConverter(ConversionFormat.MARKDOWN, ConversionFormat.DOCX);
                FormatConverter.ConversionResult docxResult = md2docx.convert(projectFiles);
                byte[] docxBytes = docxResult.data();
                allWarnings.addAll(docxResult.warnings());

                String docxKey = "quick/" + jobId + "/output.docx";
                fileStorage.putObject(docxKey, docxBytes,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                job.setDocxKey(docxKey);

                // Step 3: DOCX → PDF via converter pipeline (if needed)
                if (fmt == OutputFormat.PDF || fmt == OutputFormat.BOTH) {
                    job.setStatus("CONVERTING_PDF");
                    jobRepository.save(job);

                    FormatConverter docx2pdf = getConverter(ConversionFormat.DOCX, ConversionFormat.PDF);
                    FormatConverter.ConversionResult pdfResult = docx2pdf.convert(Map.of("output.docx", docxBytes));
                    allWarnings.addAll(pdfResult.warnings());

                    String pdfKey = "quick/" + jobId + "/result.pdf";
                    fileStorage.putObject(pdfKey, pdfResult.data(), "application/pdf");
                    job.setPdfKey(pdfKey);
                }
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
            int p = queue.indexOf(job.getId()); Long pos = p >= 0 ? (long)p : null;
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
     * Check whether raw bytes look like a ZIP archive.
     */
    private boolean isZip(byte[] data, String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".docx")) return false;
        if (filename != null && filename.toLowerCase().endsWith(".zip")) return true;
        return data.length >= 4
                && data[0] == 0x50 && data[1] == 0x4B
                && data[2] == 0x03 && data[3] == 0x04;
    }

    /**
     * Safely extract all files from a ZIP archive into an in-memory map keyed by
     * relative path.  Performs the following security checks:
     * <ul>
     *   <li>Path-traversal prevention (rejects entries with ".." or absolute paths)</li>
     *   <li>Maximum entry count ({@value #ZIP_MAX_ENTRIES})</li>
     *   <li>Maximum total uncompressed size ({@value #ZIP_MAX_TOTAL_SIZE} bytes)</li>
     *   <li>Maximum single-entry size ({@value #ZIP_MAX_ENTRY_SIZE} bytes)</li>
     *   <li>Compression-ratio bomb detection (ratio &gt; {@value #ZIP_MAX_RATIO})</li>
     *   <li>Corrupt/truncated entry detection</li>
     * </ul>
     */
    private Map<String, byte[]> extractZip(byte[] rawBytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long totalUncompressed = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(rawBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');

                // ── Path-traversal guard ──────────────────────────────
                Path normalized = Path.of(name).normalize();
                if (normalized.isAbsolute() || normalized.startsWith("..")) {
                    throw ApiException.badRequest("ZIP_PATH_TRAVERSAL",
                            "Blocked path-traversal entry: " + name);
                }
                name = normalized.toString().replace('\\', '/');

                // ── Entry count limit ─────────────────────────────────
                if (++entryCount > ZIP_MAX_ENTRIES) {
                    throw ApiException.badRequest("ZIP_TOO_MANY_ENTRIES",
                            "ZIP exceeds maximum entry count (" + ZIP_MAX_ENTRIES + ")");
                }

                // ── Read entry with size guards ───────────────────────
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long entrySize = 0;
                long compressedSize = 0;
                int n;
                while ((n = zis.read(buf)) > 0) {
                    entrySize += n;
                    if (entrySize > ZIP_MAX_ENTRY_SIZE) {
                        throw ApiException.badRequest("ZIP_ENTRY_TOO_LARGE",
                                "Entry '" + name + "' exceeds " + (ZIP_MAX_ENTRY_SIZE / (1024 * 1024)) + " MB");
                    }
                    baos.write(buf, 0, n);
                }

                totalUncompressed += entrySize;
                if (totalUncompressed > ZIP_MAX_TOTAL_SIZE) {
                    throw ApiException.badRequest("ZIP_TOO_LARGE",
                            "Total uncompressed size exceeds " + (ZIP_MAX_TOTAL_SIZE / (1024 * 1024)) + " MB");
                }

                // ── Compression-ratio bomb detection ──────────────────
                compressedSize = entry.getCompressedSize();
                if (compressedSize > 0 && entrySize > 0) {
                    double ratio = (double) entrySize / compressedSize;
                    if (ratio > ZIP_MAX_RATIO) {
                        throw ApiException.badRequest("ZIP_BOMB",
                                "Suspected zip bomb: entry '" + name + "' ratio=" + String.format("%.1f", ratio));
                    }
                }

                files.put(name, baos.toByteArray());
                log.debug("Extracted '{}' ({} bytes) from ZIP", name, entrySize);
            }
        } catch (java.util.zip.ZipException e) {
            throw ApiException.badRequest("ZIP_CORRUPT", "ZIP archive is corrupted: " + e.getMessage());
        }

        if (files.isEmpty()) {
            throw ApiException.badRequest("ZIP_EMPTY", "ZIP archive is empty");
        }

        log.info("Extracted {} files ({} bytes total) from ZIP", entryCount, totalUncompressed);
        return files;
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
