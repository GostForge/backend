package org.gostforge.backend.conversion.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.conversion.ConversionFormat;
import org.gostforge.backend.conversion.FormatConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Converts Markdown → DOCX via the md2gost microservice.
 */
@Component
@Slf4j
public class Md2GostConverter implements FormatConverter {

    @Value("${services.md2gost.url:http://localhost:8081}")
    private String md2gostUrl;

    private WebClient client;
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MAX_RETRIES = 3;
    private static final long[] DELAYS = {1000, 2000, 4000};

    @PostConstruct
    void init() {
        client = WebClient.builder()
                .baseUrl(md2gostUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    @Override
    public ConversionFormat inputFormat() {
        return ConversionFormat.MARKDOWN;
    }

    @Override
    public ConversionFormat outputFormat() {
        return ConversionFormat.DOCX;
    }

    @Override
    public ConversionResult convert(Map<String, byte[]> files) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return doConvert(files);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    log.warn("md2gost attempt {} failed, retrying in {}ms: {}",
                            attempt + 1, DELAYS[attempt], e.getMessage());
                    sleep(DELAYS[attempt]);
                } else {
                    throw new RuntimeException(
                            "md2gost conversion failed after " + MAX_RETRIES + " attempts: " + e.getMessage(), e);
                }
            }
        }
        throw new RuntimeException("md2gost unreachable");
    }

    private ConversionResult doConvert(Map<String, byte[]> files) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        boolean mdFileAdded = false;
        
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            byte[] data = entry.getValue();
            
            if (!mdFileAdded && (path.endsWith(".md") || path.endsWith(".markdown"))) {
                builder.part("file", new ByteArrayResource(data) {
                    @Override public String getFilename() { return path; }
                }).contentType(MediaType.TEXT_PLAIN);
                mdFileAdded = true;
            } else {
                builder.part("assets", new ByteArrayResource(data) {
                    @Override public String getFilename() { return path; }
                }).contentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        }

        return client.post()
                .uri("/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(new RuntimeException(
                                "md2gost error " + response.statusCode() + ": " + errorBody
                            )));
                    }
                    return response.bodyToMono(byte[].class)
                        .map(body -> {
                            if (body == null || body.length < 4) {
                                throw new RuntimeException("md2gost returned malformed response");
                            }
                            // Binary framing: [4 bytes: warnings JSON length][warnings JSON bytes][DOCX bytes]
                            int jsonLen = ((body[0] & 0xFF) << 24)
                                        | ((body[1] & 0xFF) << 16)
                                        | ((body[2] & 0xFF) << 8)
                                        |  (body[3] & 0xFF);
                            String warningsJson = new String(body, 4, jsonLen, StandardCharsets.UTF_8);
                            byte[] docxBytes = Arrays.copyOfRange(body, 4 + jsonLen, body.length);
                            if (docxBytes.length == 0) {
                                throw new RuntimeException("md2gost returned empty docx");
                            }
                            return new ConversionResult(docxBytes, parseWarnings(warningsJson));
                        });
                })
                .block();
    }

    private List<String> parseWarnings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OM.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse warnings JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry", ie);
        }
    }
}
