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

@Component
@Slf4j
public class Docx2MdConverter implements FormatConverter {

    @Value("${services.docx2md.url:http://localhost:8082}")
    private String docx2mdUrl;

    private WebClient client;
    private static final int MAX_RETRIES = 3;
    private static final long[] DELAYS = {1000, 2000, 4000};

    @PostConstruct
    void init() {
        client = WebClient.builder()
                .baseUrl(docx2mdUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    @Override
    public ConversionFormat inputFormat() {
        return ConversionFormat.DOCX;
    }

    @Override
    public ConversionFormat outputFormat() {
        return ConversionFormat.MARKDOWN;
    }

    @Override
    public ConversionResult convert(Map<String, byte[]> files) {
        byte[] docx = files.values().stream().findFirst()
            .orElseThrow(() -> new RuntimeException("No DOCX file provided"));

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return doConvert(docx);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    log.warn("docx2md attempt {} failed, retrying: {}", attempt + 1, e.getMessage());
                    sleep(DELAYS[attempt]);
                } else {
                    throw new RuntimeException("docx2md failed: " + e.getMessage(), e);
                }
            }
        }
        throw new RuntimeException("docx2md unreachable");
    }

    private ConversionResult doConvert(byte[] docx) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(docx) {
            @Override public String getFilename() { return "input.docx"; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        byte[] zipArchive = client.post()
                .uri("/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(byte[].class)
                .block();

        if (zipArchive == null || zipArchive.length == 0) {
            throw new RuntimeException("docx2md returned empty response");
        }
        return new ConversionResult(zipArchive);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
