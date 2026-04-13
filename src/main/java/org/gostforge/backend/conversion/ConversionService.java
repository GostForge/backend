package org.gostforge.backend.conversion;

import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.conversion.dto.JobStatusResponse;
import org.gostforge.backend.conversion.dto.PublicConversionBoardResponse;
import org.gostforge.backend.storage.CasService;
import org.gostforge.backend.storage.LocalFileStorageService;
import org.gostforge.backend.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class ConversionService {

    private final ConversionJobRepository jobRepository;
    private final LocalFileStorageService fileStorage;
    private final CasService casService;
    private final UserRepository userRepository;
    private final MemoryQueue queue;
    private final Map<ConversionFormat, Map<ConversionFormat, FormatConverter>> converterMap;

        private static final String STATUS_PENDING = "PENDING";
        private static final String STATUS_COMPLETED = "COMPLETED";
        private static final String STATUS_FAILED = "FAILED";
        private static final String STATUS_MERGING_MD = "MERGING_MD";
        private static final String STATUS_CONVERTING_MD = "CONVERTING_MD";
        private static final String STATUS_CONVERTING_DOCX = "CONVERTING_DOCX";
        private static final String STATUS_CONVERTING_PDF = "CONVERTING_PDF";

        private static final List<String> ACTIVE_STATUSES =
            List.of(STATUS_PENDING, STATUS_MERGING_MD, STATUS_CONVERTING_DOCX, STATUS_CONVERTING_PDF, STATUS_CONVERTING_MD);
        private static final List<String> PROCESSING_STATUSES =
            List.of(STATUS_MERGING_MD, STATUS_CONVERTING_DOCX, STATUS_CONVERTING_PDF, STATUS_CONVERTING_MD);

        private static final String EXT_MD = ".md";
        private static final String EXT_DOCX = ".docx";
        private static final String EXT_ZIP = ".zip";

        private static final String JOB_PREFIX_ROOT = "quick/";
        private static final String RESULT_ZIP_NAME = "result.zip";
        private static final String RESULT_DOCX_NAME = "result.docx";
        private static final String RESULT_PDF_NAME = "result.pdf";

        private static final String CONTENT_TYPE_MD = "text/markdown";
        private static final String CONTENT_TYPE_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        private static final String CONTENT_TYPE_PDF = "application/pdf";
        private static final String CONTENT_TYPE_ZIP = "application/zip";

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
            UserRepository userRepository,
            MemoryQueue queue,
            List<FormatConverter> converters) {
        this.jobRepository = jobRepository;
        this.fileStorage = fileStorage;
        this.casService = casService;
        this.userRepository = userRepository;
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
    public JobStatusResponse submitJob(UUID userId, String conversionChain, MultipartFile file) {
        ensureNoActiveJobs(userId);
        ConversionChain chain = ConversionChain.fromString(conversionChain);
        ConversionJob job = createPendingJob(userId, chain);
        String jobPrefix = buildJobPrefix(job.getId());

        InputPresence presence = storeUploadedInput(jobPrefix, chain, file);
        validateInputPresence(chain, presence, false);

        enqueueAfterCommit(job.getId());

        return toStatusResponse(job);
    }

    /**
     * Submit a job from VS Code manifest-based upload.
     * files: only the missing files the client re-uploaded.
     * manifest: full path→sha256 map of ALL project files.
     */
    @Transactional
    public JobStatusResponse submitJobFromManifest(UUID userId, String conversionChain,
                                                   Map<String, String> manifest,
                                                   Map<String, byte[]> uploadedFiles) {
        ensureNoActiveJobs(userId);
        ConversionChain chain = ConversionChain.fromString(conversionChain);

        verifyUploadedFilesInManifest(manifest, uploadedFiles);
        storeUploadedFilesToCas(manifest, uploadedFiles);
        ensureManifestIsPresentInCas(manifest);

        ConversionJob job = createPendingJob(userId, chain);
        String jobPrefix = buildJobPrefix(job.getId());

        InputPresence presence = restoreManifestWorkspace(jobPrefix, manifest);
        validateInputPresence(chain, presence, true);

        enqueueAfterCommit(job.getId());

        return toStatusResponse(job);
    }

    @Transactional(readOnly = true)
    public JobStatusResponse getJobStatus(UUID jobId, UUID userId) {
        ConversionJob job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> ApiException.notFound("Job not found"));
        return toStatusResponse(job);
    }

    @Transactional(readOnly = true)
    public PublicConversionBoardResponse getPublicBoard(int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        Instant now = Instant.now();
        Instant since24h = now.minus(24, ChronoUnit.HOURS);
        Instant since30d = now.minus(30, ChronoUnit.DAYS);

        Map<String, Long> allStatusCounts = toStatusCountMap(jobRepository.countByStatusGrouped());
        Map<String, Long> last24hStatusCounts = toStatusCountMap(jobRepository.countByStatusGroupedAfter(since24h));

        long totalJobs = allStatusCounts.values().stream().mapToLong(Long::longValue).sum();
        long activeJobs = ACTIVE_STATUSES.stream().mapToLong(status -> countForStatus(allStatusCounts, status)).sum();
        long completedJobs = countForStatus(allStatusCounts, STATUS_COMPLETED);
        long failedJobs = countForStatus(allStatusCounts, STATUS_FAILED);

        long submittedLast24h = last24hStatusCounts.values().stream().mapToLong(Long::longValue).sum();
        long completedLast24h = countForStatus(last24hStatusCounts, STATUS_COMPLETED);
        long failedLast24h = countForStatus(last24hStatusCounts, STATUS_FAILED);

        List<ConversionJob> recentJobs = jobRepository.findRecent(PageRequest.of(0, safeLimit));
        List<PublicConversionBoardResponse.Item> recentItems = recentJobs.stream()
                .map(this::toPublicBoardItem)
                .toList();

        return PublicConversionBoardResponse.builder()
            .generatedAt(now)
            .totalJobs(totalJobs)
            .activeJobs(activeJobs)
            .completedJobs(completedJobs)
            .failedJobs(failedJobs)
            .totalUsers(userRepository.count())
            .registeredLast24h(userRepository.countByCreatedAtAfter(since24h))
            .registeredLast30d(userRepository.countByCreatedAtAfter(since30d))
            .submittedLast24h(submittedLast24h)
            .completedLast24h(completedLast24h)
            .failedLast24h(failedLast24h)
                .recent(recentItems)
                .build();
    }

    /**
     * Called by ConversionWorker after BRPOP.
     * Uses the {@link FormatConverter} pipeline for actual conversions.
     */
    @Transactional
    public void processJob(UUID jobId) {
        ConversionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !STATUS_PENDING.equals(job.getStatus())) {
            log.warn("Skipping job {} (status={}, exists={})",
                    jobId, job != null ? job.getStatus() : "N/A", job != null);
            return;
        }

        job.setStatus(STATUS_MERGING_MD);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);

        try {
            Map<String, byte[]> projectFiles = collectProjectFiles(jobId);
            List<String> allWarnings = executeChain(job, jobId, projectFiles);

            job.setWarnings(allWarnings.isEmpty() ? null : List.copyOf(allWarnings));
            job.setStatus(STATUS_COMPLETED);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            log.info("Job {} completed successfully (warnings: {})", jobId, allWarnings.size());

        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            String currentStage = job.getStatus();
            job.setStatus(STATUS_FAILED);
            job.setErrorMessage(e.getMessage());
            if (job.getErrorStage() == null) {
                job.setErrorStage(
                    STATUS_CONVERTING_PDF.equals(currentStage)
                        || STATUS_CONVERTING_MD.equals(currentStage)
                        || STATUS_CONVERTING_DOCX.equals(currentStage)
                        ? currentStage
                        : STATUS_CONVERTING_DOCX);
            }
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
        }
    }

    private record InputPresence(boolean hasMd, boolean hasDocx) {
    }

    private void ensureNoActiveJobs(UUID userId) {
        List<ConversionJob> active = jobRepository.findActiveJobs(userId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw ApiException.conflict("ACTIVE_JOB", "You already have an active conversion job");
        }
    }

    private ConversionJob createPendingJob(UUID userId, ConversionChain chain) {
        ConversionJob job = ConversionJob.builder()
                .userId(userId)
                .conversionChain(chain)
                .build();
        return jobRepository.save(job);
    }

    private String buildJobPrefix(UUID jobId) {
        return JOB_PREFIX_ROOT + jobId + "/";
    }

    private void enqueueAfterCommit(UUID jobId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queue.push(jobId);
            }
        });
    }

    private InputPresence storeUploadedInput(String jobPrefix, ConversionChain chain, MultipartFile file) {
        boolean hasMd = false;
        boolean hasDocx = false;

        try {
            byte[] rawBytes = file.getBytes();
            String originalName = file.getOriginalFilename();

            if (isZip(rawBytes, originalName)) {
                Map<String, byte[]> extracted = extractZip(rawBytes);
                for (Map.Entry<String, byte[]> entry : extracted.entrySet()) {
                    String path = entry.getKey();
                    fileStorage.putObject(jobPrefix + path, entry.getValue(), guessContentType(path));
                    if (hasExtension(path, EXT_MD)) {
                        hasMd = true;
                    } else if (hasExtension(path, EXT_DOCX)) {
                        hasDocx = true;
                    }
                }
            } else {
                String name = resolveUploadedSingleFileName(originalName, chain);
                String contentType = hasExtension(name, EXT_DOCX)
                        ? CONTENT_TYPE_DOCX
                        : CONTENT_TYPE_MD;
                fileStorage.putObject(jobPrefix + name, rawBytes, contentType);

                if (hasExtension(name, EXT_MD)) {
                    hasMd = true;
                }
                if (hasExtension(name, EXT_DOCX)) {
                    hasDocx = true;
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store uploaded file", e);
        }

        return new InputPresence(hasMd, hasDocx);
    }

    private void verifyUploadedFilesInManifest(Map<String, String> manifest, Map<String, byte[]> uploadedFiles) {
        for (String path : uploadedFiles.keySet()) {
            if (!manifest.containsKey(path)) {
                throw ApiException.badRequest("UNKNOWN_FILE",
                        "Uploaded file '" + path + "' is not in manifest");
            }
        }
    }

    private void storeUploadedFilesToCas(Map<String, String> manifest, Map<String, byte[]> uploadedFiles) {
        for (Map.Entry<String, byte[]> entry : uploadedFiles.entrySet()) {
            String path = entry.getKey();
            String declaredHash = manifest.get(path);
            casService.storeVerified(declaredHash, entry.getValue(), path);
        }
    }

    private void ensureManifestIsPresentInCas(Map<String, String> manifest) {
        List<String> stillMissing = new ArrayList<>();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            if (!casService.exists(entry.getValue())) {
                stillMissing.add(entry.getKey());
            }
        }
        if (!stillMissing.isEmpty()) {
            throw new StaleCacheException(stillMissing);
        }
    }

    private InputPresence restoreManifestWorkspace(String jobPrefix, Map<String, String> manifest) {
        boolean hasMd = false;
        boolean hasDocx = false;

        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            byte[] data = casService.get(hash);
            if (data == null) {
                throw new RuntimeException("CAS miss after verification: " + path);
            }

            fileStorage.putObject(jobPrefix + path, data, guessContentType(path));
            if (hasExtension(path, EXT_MD)) {
                hasMd = true;
            } else if (hasExtension(path, EXT_DOCX)) {
                hasDocx = true;
            }
        }

        return new InputPresence(hasMd, hasDocx);
    }

    private void validateInputPresence(ConversionChain chain, InputPresence presence, boolean fromManifest) {
        if (chain.requiresMarkdownInput()) {
            if (!presence.hasMd()) {
                String msg = fromManifest
                        ? "Manifest contains no .md file"
                        : "Upload contains no .md file";
                throw ApiException.badRequest("NO_MD_FILE", msg);
            }
            return;
        }

        if (!presence.hasDocx()) {
            String msg = fromManifest
                    ? "Manifest contains no .docx file"
                    : "Upload contains no .docx file";
            throw ApiException.badRequest("NO_DOCX_FILE", msg);
        }
    }

    private Map<String, byte[]> collectProjectFiles(UUID jobId) {
        String jobPrefix = buildJobPrefix(jobId);
        Map<String, byte[]> projectFiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : fileStorage.listObjects(jobPrefix)) {
            if (!key.startsWith(jobPrefix)) {
                continue;
            }
            String relativePath = key.substring(jobPrefix.length());
            if (!relativePath.isEmpty()) {
                projectFiles.put(relativePath, fileStorage.getObject(key));
            }
        }
        return projectFiles;
    }

    private List<String> executeChain(ConversionJob job, UUID jobId, Map<String, byte[]> projectFiles) {
        if (job.getConversionChain().producesZipResult()) {
            return runDocxToMdChain(job, jobId, projectFiles);
        }
        return runMarkdownChain(job, jobId, projectFiles);
    }

    private List<String> runDocxToMdChain(ConversionJob job, UUID jobId, Map<String, byte[]> projectFiles) {
        job.setStatus(STATUS_CONVERTING_MD);
        jobRepository.save(job);

        FormatConverter docx2md = getConverter(ConversionFormat.DOCX, ConversionFormat.MARKDOWN);
        FormatConverter.ConversionResult mdResult = docx2md.convert(projectFiles);

        String zipKey = buildJobPrefix(jobId) + RESULT_ZIP_NAME;
        fileStorage.putObject(zipKey, mdResult.data(), CONTENT_TYPE_ZIP);
        job.setResultKey(zipKey);
        job.setResultType(ConversionResultType.ZIP);
        return new ArrayList<>(mdResult.warnings());
    }

    private List<String> runMarkdownChain(ConversionJob job, UUID jobId, Map<String, byte[]> projectFiles) {
        job.setStatus(STATUS_CONVERTING_DOCX);
        jobRepository.save(job);

        FormatConverter md2docx = getConverter(ConversionFormat.MARKDOWN, ConversionFormat.DOCX);
        FormatConverter.ConversionResult docxResult = md2docx.convert(projectFiles);
        byte[] docxBytes = docxResult.data();

        List<String> warnings = new ArrayList<>(docxResult.warnings());
        if (job.getConversionChain().producesPdfResult()) {
            job.setStatus(STATUS_CONVERTING_PDF);
            jobRepository.save(job);

            FormatConverter docx2pdf = getConverter(ConversionFormat.DOCX, ConversionFormat.PDF);
            FormatConverter.ConversionResult pdfResult = docx2pdf.convert(Map.of("output.docx", docxBytes));
            warnings.addAll(pdfResult.warnings());

            String pdfKey = buildJobPrefix(jobId) + RESULT_PDF_NAME;
            fileStorage.putObject(pdfKey, pdfResult.data(), CONTENT_TYPE_PDF);
            job.setResultKey(pdfKey);
            job.setResultType(ConversionResultType.PDF);
            return warnings;
        }

        String docxKey = buildJobPrefix(jobId) + RESULT_DOCX_NAME;
        fileStorage.putObject(docxKey, docxBytes, CONTENT_TYPE_DOCX);
        job.setResultKey(docxKey);
        job.setResultType(ConversionResultType.DOCX);
        return warnings;
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

    private Map<String, Long> toStatusCountMap(List<ConversionJobRepository.StatusCountRow> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (ConversionJobRepository.StatusCountRow row : rows) {
            counts.put(row.getStatus(), row.getCnt());
        }
        return counts;
    }

    private long countForStatus(Map<String, Long> counts, String status) {
        return counts.getOrDefault(status, 0L);
    }

    private JobStatusResponse toStatusResponse(ConversionJob job) {
        Integer queuePos = null;
        if (STATUS_PENDING.equals(job.getStatus())) {
            int p = queue.indexOf(job.getId());
            Long pos = p >= 0 ? (long) p : null;
            if (pos != null && pos >= 0) {
                queuePos = pos.intValue() + 1;
            }
        }

        List<String> warnings = job.getWarnings() == null ? List.of() : job.getWarnings();

        return JobStatusResponse.builder()
                .jobId(job.getId())
                .status(job.getStatus())
                .queuePosition(queuePos)
                .conversionChain(job.getConversionChain().name())
                .errorStage(job.getErrorStage())
                .errorMessage(job.getErrorMessage())
                .warnings(warnings.isEmpty() ? null : warnings)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    private PublicConversionBoardResponse.Item toPublicBoardItem(ConversionJob job) {
        Instant completedAt = job.getCompletedAt();
        Instant createdAt = job.getCreatedAt();
        Long durationMs = null;
        if (createdAt != null && completedAt != null) {
            durationMs = Math.max(0, ChronoUnit.MILLIS.between(createdAt, completedAt));
        }

        List<String> warnings = job.getWarnings() == null ? List.of() : job.getWarnings();

        return PublicConversionBoardResponse.Item.builder()
                .publicId(job.getId() != null ? job.getId().toString().substring(0, 8) : "unknown")
                .status(job.getStatus())
            .conversionChain(job.getConversionChain().name())
                .createdAt(createdAt)
                .completedAt(completedAt)
                .durationMs(durationMs)
                .warningCount(warnings.size())
                .hasError(job.getErrorMessage() != null && !job.getErrorMessage().isBlank())
                .build();
    }

    /**
     * Check whether raw bytes look like a ZIP archive.
     */
    private boolean isZip(byte[] data, String filename) {
        if (hasExtension(filename, EXT_DOCX)) return false;
        if (hasExtension(filename, EXT_ZIP)) return true;
        return data.length >= 4
                && data[0] == 0x50 && data[1] == 0x4B
                && data[2] == 0x03 && data[3] == 0x04;
    }

    private boolean hasExtension(String filename, String extension) {
        if (filename == null || extension == null) {
            return false;
        }
        return filename.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT));
    }

    private String resolveUploadedSingleFileName(String originalName, ConversionChain chain) {
        if (hasExtension(originalName, EXT_DOCX) || hasExtension(originalName, EXT_MD)) {
            return originalName;
        }
        return chain.requiresMarkdownInput() ? "input.md" : "input.docx";
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
        if (path.endsWith(EXT_MD)) return CONTENT_TYPE_MD;
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
