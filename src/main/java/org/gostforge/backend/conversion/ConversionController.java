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
    private final LocalFileStorageService minioStorage;
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

        ConversionChain chain = ConversionChain.fromString(job.getConversionChain());
        String key;
        String contentType;
        String filename;
        if (chain.producesZipResult()) {
            key = job.getMergedMdKey();
            contentType = "application/zip";
            filename = "result.zip";
        } else if (chain.producesPdfResult()) {
            key = job.getPdfKey();
            contentType = "application/pdf";
            filename = "output.pdf";
        } else {
            key = job.getDocxKey();
            contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            filename = "output.docx";
        }

        if (key == null) {
            throw ApiException.notFound("Result file is missing");
        }

        InputStream stream = minioStorage.getObjectStream(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new InputStreamResource(stream));
    }

    private String resolveChainFromOptions(String directChainValue, String optionsJson) {
        if (optionsJson != null && !optionsJson.isBlank()) {
            try {
                JsonNode node = OM.readTree(optionsJson);
                JsonNode chain = node.get("conversionChain");
                if (chain != null && !chain.isNull() && !chain.asText().isBlank()) {
                    return ConversionChain.fromString(chain.asText()).name();
                }
                JsonNode fmt = node.get("outputFormat");
                if (fmt != null && !fmt.isNull() && !fmt.asText().isBlank()) {
                    directChainValue = mapLegacyOutputFormat(fmt.asText());
                }
            } catch (Exception ignored) { }
        }
        return ConversionChain.fromString(directChainValue).name();
    }

    private String resolveConversionChain(String directChain, String outputFormat, String optionsJson) {
        String chainCandidate = directChain;
        if (chainCandidate == null || chainCandidate.isBlank()) {
            chainCandidate = outputFormat != null ? mapLegacyOutputFormat(outputFormat) : null;
        }
        return resolveChainFromOptions(chainCandidate, optionsJson);
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
                throw new RuntimeException("Failed to read part: " + name, e);
            }
        }
        return result;
    }
}
