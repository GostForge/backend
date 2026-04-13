package org.gostforge.backend.conversion;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.conversion.dto.JobStatusResponse;
import org.gostforge.backend.conversion.dto.PublicConversionBoardResponse;
import org.gostforge.backend.storage.LocalFileStorageService;
import org.gostforge.backend.storage.CasService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversions")
public class ConversionController {

    private final ConversionService conversionService;
    private final ConversionJobRepository jobRepository;
    private final LocalFileStorageService fileStorage;
    private final CasService casService;

    private static final ObjectMapper OM = new ObjectMapper();

    @GetMapping("/public/board")
    public ResponseEntity<PublicConversionBoardResponse> getPublicBoard(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(conversionService.getPublicBoard(limit));
    }
    
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
            @RequestParam(value = "conversionChain", required = false) String conversionChain,
            @RequestParam(value = "outputFormat", required = false) String outputFormat,
            @RequestParam(value = "options", required = false) String optionsJson) {

        UUID userId = (UUID) auth.getPrincipal();
        String resolvedChain = resolveConversionChain(conversionChain, outputFormat, optionsJson);

        if (manifestJson != null && !manifestJson.isBlank()) {
            Map<String, String> manifest = parseManifest(manifestJson);
            Map<String, byte[]> uploadedFiles = collectUploadedFiles(files);
            try {
                return ResponseEntity.accepted().body(
                        conversionService.submitJobFromManifest(userId, resolvedChain, manifest, uploadedFiles));
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
                conversionService.submitJob(userId, resolvedChain, input));
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

        String key = job.getResultKey();
        ConversionResultType resultType = job.getResultType();
        if (key == null || resultType == null) {
            throw ApiException.notFound("Result file is missing");
        }

        String contentType;
        String filename;
        switch (resultType) {
            case ZIP -> {
                contentType = "application/zip";
                filename = "result.zip";
            }
            case PDF -> {
                contentType = "application/pdf";
                filename = "result.pdf";
            }
            case DOCX -> {
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                filename = "result.docx";
            }
            default -> throw ApiException.notFound("Result file type is missing");
        }

        InputStream stream = fileStorage.getObjectStream(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new InputStreamResource(stream));
    }

    private String resolveConversionChain(String directChain, String outputFormat, String optionsJson) {
        String chainCandidate = directChain;
        String legacyFormatCandidate = outputFormat;

        if (optionsJson != null && !optionsJson.isBlank()) {
            try {
                JsonNode node = OM.readTree(optionsJson);
                JsonNode chain = node.get("conversionChain");
                if ((chainCandidate == null || chainCandidate.isBlank())
                        && chain != null && !chain.isNull() && !chain.asText().isBlank()) {
                    chainCandidate = chain.asText();
                }

                JsonNode fmt = node.get("outputFormat");
                if ((legacyFormatCandidate == null || legacyFormatCandidate.isBlank())
                        && fmt != null && !fmt.isNull() && !fmt.asText().isBlank()) {
                    legacyFormatCandidate = fmt.asText();
                }
            } catch (Exception e) {
                // Keep fallback behavior for malformed options JSON.
            }
        }

        if (chainCandidate == null || chainCandidate.isBlank()) {
            chainCandidate = mapLegacyOutputFormat(legacyFormatCandidate);
        }

        return ConversionChain.fromString(chainCandidate).name();
    }

    private String mapLegacyOutputFormat(String outputFormat) {
        if (outputFormat == null || outputFormat.isBlank()) {
            return ConversionChain.MD_TO_DOCX.name();
        }
        return switch (outputFormat.toUpperCase()) {
            case "PDF", "BOTH" -> ConversionChain.MD_TO_DOCX_TO_PDF.name();
            case "MARKDOWN" -> ConversionChain.DOCX_TO_MD.name();
            default -> ConversionChain.MD_TO_DOCX.name();
        };
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
                throw ApiException.badRequest("INVALID_PART", "Failed to read multipart item: " + name);
            }
        }
        return result;
    }
}
