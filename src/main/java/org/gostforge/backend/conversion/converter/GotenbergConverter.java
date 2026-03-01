package org.gostforge.backend.conversion.converter;

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
import java.util.Map;

/**
 * Converts DOCX → PDF via the Gotenberg microservice (LibreOffice).
 */
@Component
@Slf4j
public class GotenbergConverter implements FormatConverter {

    @Value("${services.gotenberg.url:http://localhost:3000}")
    private String gotenbergUrl;

    private WebClient client;
    private static final int MAX_RETRIES = 3;
    private static final long[] DELAYS = {1000, 2000, 4000};

    @PostConstruct
    void init() {
        client = WebClient.builder()
                .baseUrl(gotenbergUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    @Override
    public ConversionFormat inputFormat() {
        return ConversionFormat.DOCX;
    }

    @Override
    public ConversionFormat outputFormat() {
        return ConversionFormat.PDF;
    }

    @Override
    public ConversionResult convert(byte[] input, Map<String, byte[]> assets) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return doConvert(input);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    log.warn("Gotenberg attempt {} failed, retrying in {}ms: {}",
                            attempt + 1, DELAYS[attempt], e.getMessage());
                    sleep(DELAYS[attempt]);
                } else {
                    throw new RuntimeException(
                            "Gotenberg PDF conversion failed after " + MAX_RETRIES + " attempts: " + e.getMessage(), e);
                }
            }
        }
        throw new RuntimeException("Gotenberg unreachable");
    }

    private ConversionResult doConvert(byte[] docxBytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", new ByteArrayResource(docxBytes) {
            @Override public String getFilename() { return "output.docx"; }
        }).contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        byte[] result = client.post()
                .uri("/forms/libreoffice/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(byte[].class)
                .block();

        if (result == null || result.length == 0) {
            throw new RuntimeException("Gotenberg returned empty response");
        }
        return new ConversionResult(result);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry", ie);
        }
    }
}
