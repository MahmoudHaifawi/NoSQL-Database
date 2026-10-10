package com.database.atypon.DBMS.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the gateway service layer (the logic above the forwarders) against an in-process stub node:
 * ReadService flattens the node's {@code {"<id>.json": "<doc>"}} map into an id-ordered map, and
 * WriteService parses the node's response array into a new version or a surfaced error.
 */
class DbmsServiceHttpTest {

    private HttpServer server;
    private String baseUrl;
    private volatile String cannedBody = "{}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] resp = cannedBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void readServiceFlattensAndOrdersDocumentsById() {
        // node returns an unordered map of "<id>.json" -> "<doc json string>"
        cannedBody = "{\"content\":\"{\\\"2.json\\\":\\\"{\\\\\\\"n\\\\\\\":2}\\\",\\\"0.json\\\":\\\"{\\\\\\\"n\\\\\\\":0}\\\",\\\"10.json\\\":\\\"{\\\\\\\"n\\\\\\\":10}\\\"}\"}";
        LinkedHashMap<String, String> docs = new ReadService().readAll("shop", "products", "jwt", baseUrl);
        assertThat(docs.keySet()).containsExactly("0", "2", "10"); // numeric id order, not string order
        assertThat(docs.get("0")).contains("\"n\": 0");
    }

    @Test
    void writeServiceUpdateReturnsNewVersion() throws Exception {
        cannedBody = "[{\"responseType\":\"SUCCESS\",\"message\":\"Document updated successfully\",\"content\":2}]";
        int newVersion = new WriteService().updateDocument("shop", "products", "0",
                "{\"n\":8}", 1, "jwt", baseUrl);
        assertThat(newVersion).isEqualTo(2);
    }

    @Test
    void writeServiceUpdateThrowsOnConflict() {
        cannedBody = "[{\"responseType\":\"ERROR\",\"message\":\"Version conflict: document is at version 5\",\"content\":5}]";
        assertThatThrownBy(() -> new WriteService().updateDocument("shop", "products", "0",
                "{\"n\":8}", 1, "jwt", baseUrl))
                .hasMessageContaining("Version conflict");
    }

    @Test
    void writeServiceDeleteThrowsOnConflict() {
        cannedBody = "[{\"responseType\":\"ERROR\",\"message\":\"Version conflict: document is at version 2\"}]";
        assertThatThrownBy(() -> new WriteService().deleteDocument("shop", "products", "0", 1, "jwt", baseUrl))
                .hasMessageContaining("conflict");
    }
}
