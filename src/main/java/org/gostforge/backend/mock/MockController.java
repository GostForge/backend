package org.gostforge.backend.mock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/_mock")
@Profile("mock")
@RequiredArgsConstructor
@Slf4j
public class MockController {

    private final MockState state;

    @PostMapping("/admin/reset")
    public ResponseEntity<String> reset() {
        state.setGlobalDelayMs(0);
        state.setMd2gostStatusCode(200);
        state.setGotenbergStatusCode(200);
        state.setDocx2mdStatusCode(200);
        state.setMd2gostDelayMs(0);
        state.setGotenbergDelayMs(0);
        state.setDocx2mdDelayMs(0);
        log.info("Mock state reset to normal operations.");
        return ResponseEntity.ok("Mock state reset");
    }

    @PostMapping("/admin/config")
    public ResponseEntity<MockState> config(
            @RequestParam(required = false) Integer globalDelay,
            @RequestParam(required = false) Integer md2gostStatus,
            @RequestParam(required = false) Integer md2gostDelay,
            @RequestParam(required = false) Integer gotenbergStatus,
            @RequestParam(required = false) Integer gotenbergDelay,
            @RequestParam(required = false) Integer docx2mdStatus,
            @RequestParam(required = false) Integer docx2mdDelay) {
        
        if (globalDelay != null) state.setGlobalDelayMs(globalDelay);
        if (md2gostStatus != null) state.setMd2gostStatusCode(md2gostStatus);
        if (md2gostDelay != null) state.setMd2gostDelayMs(md2gostDelay);
        if (gotenbergStatus != null) state.setGotenbergStatusCode(gotenbergStatus);
        if (gotenbergDelay != null) state.setGotenbergDelayMs(gotenbergDelay);
        if (docx2mdStatus != null) state.setDocx2mdStatusCode(docx2mdStatus);
        if (docx2mdDelay != null) state.setDocx2mdDelayMs(docx2mdDelay);
        
        log.info("Mock state updated: {}", state);
        return ResponseEntity.ok(state);
    }

    @PostMapping(value = "/md2gost/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> md2gostConvert() throws InterruptedException {
        log.info("Mock /md2gost/convert called");
        applyDelay(state.getMd2gostDelayMs());
        
        if (state.getMd2gostStatusCode() != 200) {
            return ResponseEntity.status(state.getMd2gostStatusCode()).body(("Mock Error " + state.getMd2gostStatusCode()).getBytes());
        }

        // Expected format: [4 bytes json len][json string][docx bytes]
        String warningsJson = "[\"Mock md2gost warning: Some element unsupported\"]";
        byte[] jsonBytes = warningsJson.getBytes(StandardCharsets.UTF_8);
        byte[] dummyDocx = "PK\\x03\\x04... dummy zip/docx bytes for md2gost".getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[4 + jsonBytes.length + dummyDocx.length];
        int len = jsonBytes.length;
        result[0] = (byte) ((len >> 24) & 0xFF);
        result[1] = (byte) ((len >> 16) & 0xFF);
        result[2] = (byte) ((len >> 8) & 0xFF);
        result[3] = (byte) (len & 0xFF);

        System.arraycopy(jsonBytes, 0, result, 4, len);
        System.arraycopy(dummyDocx, 0, result, 4 + len, dummyDocx.length);

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(result);
    }

    @PostMapping(value = "/gotenberg/forms/libreoffice/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> gotenbergConvert() throws InterruptedException {
        log.info("Mock /gotenberg/forms/libreoffice/convert called");
        applyDelay(state.getGotenbergDelayMs());
        
        if (state.getGotenbergStatusCode() != 200) {
            return ResponseEntity.status(state.getGotenbergStatusCode()).body(("Mock Error " + state.getGotenbergStatusCode()).getBytes());
        }

        byte[] dummyPdf = "%PDF-1.4\n% dummy pdf bytes from mock gotenberg".getBytes();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(dummyPdf);
    }

    @PostMapping(value = "/docx2md/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> docx2mdConvert() throws InterruptedException, IOException {
        log.info("Mock /docx2md/convert called");
        applyDelay(state.getDocx2mdDelayMs());
        
        if (state.getDocx2mdStatusCode() != 200) {
            return ResponseEntity.status(state.getDocx2mdStatusCode()).body(("Mock Error " + state.getDocx2mdStatusCode()).getBytes());
        }

        // Must return a ZIP archive with output.md inside
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("output.md");
            zos.putNextEntry(entry);
            zos.write("# Mock DOCX2MD Result\\nIt worked!".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).body(baos.toByteArray());
    }

    private void applyDelay(int specificDelay) throws InterruptedException {
        int totalDelay = state.getGlobalDelayMs() + specificDelay;
        if (totalDelay > 0) {
            log.info("Mock injecting delay of {} ms", totalDelay);
            Thread.sleep(totalDelay);
        }
    }
}
