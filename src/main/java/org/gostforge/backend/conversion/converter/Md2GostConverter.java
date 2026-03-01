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
    public ConversionResult convert(byte[] input, Map<String, byte[]> assets) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return doConvert(input, assets);
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

    private ConversionResult doConvert(byte[] mdBytes, Map<String, byte[]> assets) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(mdBytes) {
            @Override public String getFilename() { return "document.md"; }
        }).contentType(MediaType.TEXT_PLAIN);

        for (Map.Entry<String, byte[]> asset : assets.entrySet()) {
            String path = asset.getKey();
            byte[] data = asset.getValue();
            builder.part("assets", new ByteArrayResource(data) {
                @Override public String getFilename() { return path; }
            }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        }

        return client.post()
                .uri("/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchangeToMono(response -> {
                    List<String> warnings = parseWarningsHeader(
                            response.headers().asHttpHeaders().getFirst("X-Conversion-Warnings"));
                    return response.bodyToMono(byte[].class)
                            .map(body -> {
                                if (body == null || body.length == 0) {
                                    throw new RuntimeException("md2gost returned empty response");
                                }
                                return new ConversionResult(body, warnings);
                            });
                })
                .block();
    }

    private List<String> parseWarningsHeader(String header) {
        if (header == null || header.isBlank()) return List.of();
        try {
            return OM.readValue(header, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse X-Conversion-Warnings header: {}", e.getMessage());
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
