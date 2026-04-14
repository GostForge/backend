package org.gostforge.backend.conversion.converter;

import com.sun.net.httpserver.HttpServer;
import org.gostforge.backend.conversion.FormatConverter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class Md2GostConverterMultipartContractTest {

    @Test
    void sendsFilesMultipartFieldWithoutLegacyBrackets() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/convert", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(requestBytes, StandardCharsets.ISO_8859_1);
            capturedBody.set(body);

            if (!body.contains("name=\"files\"") || body.contains("name=\"files[]\"")) {
                byte[] error = "{\"detail\":\"invalid multipart field\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(422, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                return;
            }

            byte[] warningsJson = "[]".getBytes(StandardCharsets.UTF_8);
            byte[] docx = "DOCX".getBytes(StandardCharsets.UTF_8);
            ByteBuffer payload = ByteBuffer.allocate(4 + warningsJson.length + docx.length);
            payload.putInt(warningsJson.length);
            payload.put(warningsJson);
            payload.put(docx);
            byte[] response = payload.array();

            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Md2GostConverter converter = new Md2GostConverter();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(converter, "md2gostUrl", baseUrl);
            ReflectionTestUtils.invokeMethod(converter, "init");

            Map<String, byte[]> files = Map.of("report.md", "# report".getBytes(StandardCharsets.UTF_8));
            FormatConverter.ConversionResult result = converter.convert(files);

            assertArrayEquals("DOCX".getBytes(StandardCharsets.UTF_8), result.data());
            assertTrue(capturedBody.get().contains("name=\"files\""));
            assertFalse(capturedBody.get().contains("name=\"files[]\""));
        } finally {
            server.stop(0);
        }
    }
}
